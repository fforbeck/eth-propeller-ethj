package org.adridadou.ethereum.ethj;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.ReplaySubject;
import org.adridadou.ethereum.propeller.EthereumBackend;
import org.adridadou.ethereum.propeller.event.BlockInfo;
import org.adridadou.ethereum.propeller.event.EthereumEventHandler;
import org.adridadou.ethereum.propeller.exception.EthereumApiException;
import org.adridadou.ethereum.propeller.values.*;
import org.ethereum.config.BlockchainNetConfig;
import org.ethereum.config.blockchain.DaoNoHFConfig;
import org.ethereum.config.blockchain.HomesteadConfig;
import org.ethereum.config.blockchain.PetersburgConfig;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.ethereum.util.ByteUtil;
import org.ethereum.util.blockchain.StandaloneBlockchain;
import org.ethereum.vm.LogInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Created by davidroon on 20.01.17.
 * This code is released under Apache 2 license
 */
public class EthereumTest implements EthereumBackend {
    private final StandaloneBlockchain blockchain;
    private final TestConfig testConfig;
    private final ReplaySubject<Transaction> transactionPublisher = ReplaySubject.create(100000);
    private final Flowable<Transaction> transactionObservable = transactionPublisher.toFlowable(BackpressureStrategy.ERROR);
    private final LocalExecutionService localExecutionService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Flowable<Transaction> txProcessorFlow;

    private Logger logger = LoggerFactory.getLogger(EthereumTest.class);

    public EthereumTest(TestConfig testConfig) {
        this.blockchain = new StandaloneBlockchain()
                .withNetConfig(getBlockchainConfig())
                .withGasLimit(testConfig.getGasLimit())
                .withGasPrice(testConfig.getGasPrice().getPrice().inWei().longValue())
                .withCurrentTime(testConfig.getInitialTime());

        testConfig.getBalances().forEach((key, value) -> blockchain.withAccountBalance(key.getAddress().address, value.inWei()));

        localExecutionService = new LocalExecutionService(blockchain.getBlockchain());
        this.testConfig = testConfig;
        this.txProcessorFlow = processTransactions();
        this.txProcessorFlow.subscribe(tx -> executor.submit(() -> process(tx)));
    }

    private BlockchainNetConfig getBlockchainConfig() {
        return new PetersburgConfig(new DaoNoHFConfig(new HomesteadConfig(new HomesteadConfig.HomesteadConstants() {
            @Override
            public BigInteger getMINIMUM_DIFFICULTY() {
                return BigInteger.ONE;
            }
        }), 0));
    }

    private Flowable<Transaction> processTransactions() {
        return transactionObservable
                .onBackpressureDrop()
                .doOnError(err -> logger.error(err.getMessage(), err))
                .doOnNext(tx -> logger.info("New transaction to process: " + tx.toString()))
                .doOnComplete(() -> logger.info("Tx processed"))
                .doOnTerminate(() -> logger.info("Tx Processor terminated"))
                .observeOn(Schedulers.from(executor));
    }

    private void process(Transaction tx) {
        try {
            blockchain.submitTransaction(tx);
            blockchain.createBlock();
            logger.info("[New block created]");
        } catch (Throwable e) {
            throw new EthereumApiException("error while polling transactions for test env", e);
        }
    }

    @Override
    public GasPrice getGasPrice() {
        return testConfig.getGasPrice();
    }

    @Override
    public EthValue getBalance(EthAddress address) {
        return EthValue.wei(blockchain.getBlockchain().getRepository().getBalance(address.address));
    }

    @Override
    public boolean addressExists(EthAddress address) {
        return blockchain.getBlockchain().getRepository().isExist(address.address);
    }

    @Override
    public EthHash submit(TransactionRequest request, Nonce nonce) {
        Transaction tx = createTransaction(request, nonce);
        transactionPublisher.onNext(tx);
        return EthHash.of(tx.getHash());
    }

    private Transaction createTransaction(TransactionRequest request, Nonce nonce) {
        Transaction transaction = new Transaction(ByteUtil.bigIntegerToBytes(nonce.getValue()), ByteUtil.bigIntegerToBytes(BigInteger.ZERO), ByteUtil.bigIntegerToBytes(request.getGasLimit().getUsage()), request.getAddress().address, ByteUtil.bigIntegerToBytes(request.getValue().inWei()), request.getData().data, null);
        transaction.sign(getKey(request.getAccount()));
        return transaction;
    }

    @Override
    public GasUsage estimateGas(final EthAccount account, final EthAddress address, final EthValue value, final EthData data) {
        return new GasUsage(BigInteger.valueOf(testConfig.getGasLimit() - 1));
    }

    @Override
    public Nonce getNonce(EthAddress currentAddress) {
        return new Nonce(blockchain.getBlockchain().getRepository().getNonce(currentAddress.address));
    }

    @Override
    public long getCurrentBlockNumber() {
        return blockchain.getBlockchain().getBestBlock().getNumber();
    }

    @Override
    public Optional<BlockInfo> getBlock(long blockNumber) {
        return Optional.ofNullable(blockchain.getBlockchain().getBlockByNumber(blockNumber)).map(this::toBlockInfo);
    }

    @Override
    public Optional<BlockInfo> getBlock(EthHash blockNumber) {
        return Optional.ofNullable(blockchain.getBlockchain().getBlockByHash(blockNumber.data)).map(this::toBlockInfo);
    }

    @Override
    public SmartContractByteCode getCode(EthAddress address) {
        return SmartContractByteCode.of(blockchain.getBlockchain().getRepository().getCode(address.address));
    }

    @Override
    public synchronized EthData constantCall(final EthAccount account, final EthAddress address, final EthValue value, final EthData data) {
        return localExecutionService.executeLocally(account, address, value, data);
    }

    @Override
    public void register(EthereumEventHandler eventHandler) {
        eventHandler.onReady();
        blockchain.addEthereumListener(new EthJEventListener(eventHandler));
    }

    @Override
    public Optional<TransactionInfo> getTransactionInfo(EthHash hash) {
        return Optional.ofNullable(blockchain.getBlockchain().getTransactionInfo(hash.data)).map(info -> {
            EthHash blockHash = EthHash.of(info.getBlockHash());
            TransactionStatus status = info.isPending() ? TransactionStatus.Pending : blockHash.isEmpty() ? TransactionStatus.Unknown : TransactionStatus.Executed;
            return new TransactionInfo(hash, EthJEventListener.toReceipt(info.getReceipt(), blockHash), status, blockHash);
        });
    }

    private ECKey getKey(EthAccount account) {
        return ECKey.fromPrivate(account.getBigIntPrivateKey());
    }

    BlockInfo toBlockInfo(Block block) {
        return new BlockInfo(block.getNumber(), block.getTransactionsList().stream()
                .map(tx -> this.toReceipt(tx, EthHash.of(block.getHash()))).collect(Collectors.toList()));
    }

    private TransactionReceipt toReceipt(Transaction tx, EthHash blockHash) {
        EthValue value = tx.getValue().length == 0 ? EthValue.wei(0) : EthValue.wei(new BigInteger(1, tx.getValue()));
        List<LogInfo> logs = blockchain.getBlockchain().getTransactionInfo(tx.getHash()).getReceipt().getLogInfoList();
        return new TransactionReceipt(
                EthHash.of(tx.getHash()),
                blockHash,
                EthAddress.of(tx.getSender()),
                EthAddress.of(tx.getReceiveAddress()),
                EthAddress.empty(),
                EthData.of(tx.getData()),
                "",
                EthData.empty(),
                true,
                EthJEventListener.createEventInfoList(EthHash.of(tx.getHash()), logs), value);
    }
}
