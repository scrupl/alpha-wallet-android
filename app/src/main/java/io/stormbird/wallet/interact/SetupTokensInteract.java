package io.stormbird.wallet.interact;

/**
 * Created by James on 16/01/2018.
 */

import android.util.Log;

import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.stormbird.wallet.R;
import io.stormbird.wallet.entity.ERC875ContractTransaction;
import io.stormbird.wallet.entity.NetworkInfo;
import io.stormbird.wallet.entity.Token;
import io.stormbird.wallet.entity.TokenInfo;
import io.stormbird.wallet.entity.TokenTransaction;
import io.stormbird.wallet.entity.Transaction;
import io.stormbird.wallet.entity.TransactionContract;
import io.stormbird.wallet.entity.TransactionDecoder;
import io.stormbird.wallet.entity.TransactionInput;
import io.stormbird.wallet.entity.TransactionOperation;
import io.stormbird.wallet.entity.TransactionType;
import io.stormbird.wallet.entity.Wallet;
import io.stormbird.wallet.repository.TokenRepositoryType;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import io.stormbird.wallet.service.TokensService;

public class SetupTokensInteract {

    private final static String TAG = "STI";
    private final TokenRepositoryType tokenRepository;
    private TransactionDecoder transactionDecoder = new TransactionDecoder();
    private Map<String, Token> contractMap = new ConcurrentHashMap<>();
    private List<String> unknownContracts = new ArrayList<>();
    private String walletAddr;

    public static final String UNKNOWN_CONTRACT = "[Unknown Contract]";
    public static final String EXPIRED_CONTRACT = "[Expired Contract]";
    public static final String INVALID_OPERATION = "[Invalid Operation]";
    public static final String CONTRACT_CONSTRUCTOR = "Contract Creation";
    public static final String RECEIVE_FROM_MAGICLINK = "Receive From MagicLink";


    public SetupTokensInteract(TokenRepositoryType tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    public Observable<TokenInfo> update(String address) {
        return tokenRepository.update(address)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public void clearAll()
    {
        contractMap.clear();
        unknownContracts.clear();
        walletAddr = null;
    }

    public void setWalletAddr(String addr) { walletAddr = addr; }

    /**
     * Is the user's wallet involved in this contract's transaction?
     * (we may have obtained the transaction by peeking at the list of transactions associated with a contract)
     *
     * @param trans
     * @param data
     * @param wallet
     * @return
     */
    private boolean walletInvolvedInTransaction(Transaction trans, TransactionInput data, Wallet wallet)
    {
        boolean involved = false;
        if (data == null || data.functionData == null)
        {
            return (trans.from.equalsIgnoreCase(walletAddr)); //early return
        }
        String walletAddr = Numeric.cleanHexPrefix(wallet.address);
        if (data.containsAddress(wallet.address)) involved = true;
        if (trans.from.contains(walletAddr)) involved = true;
        if (trans.operations != null && trans.operations.length > 0 && trans.operations[0].walletInvolvedWithTransaction(wallet.address)) involved = true;
        return involved;
    }

    //use this function to generate unit test string
    private void generateTestString(Transaction[] txList)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("String[] inputTestList = {");
        boolean first = true;
        for (Transaction t : txList) {
            if (!first) {
                sb.append("\n,");
            }
            first = false;
            sb.append("\"");
            sb.append(t.input);
            sb.append("\"");
        }

        sb.append("};");
    }

    public void addTokenToMap(Token token)
    {
        contractMap.put(token.getAddress(), token);
    }

    /**
     * Transforms an array of Token - Transaction pairs into a processed list of transactions
     * Bascially just processes each set of transactions if they involve the wallet
     * @param txList
     * @param wallet
     * @return
     */
    public Observable<Transaction[]> processTokenTransactions(Wallet wallet, TokenTransaction[] txList, TokensService tokensService)
    {
        return Observable.fromCallable(() -> {
            List<Transaction> processedTransactions = new ArrayList<Transaction>();
            Token token = null;
            long highestBlock = 0;
            try {
                for (TokenTransaction thisTokenTrans : txList) {
                    Transaction thisTrans = thisTokenTrans.transaction;
                    TransactionInput data = transactionDecoder.decodeInput(thisTrans.input);
                    token = thisTokenTrans.token;

                    if (walletInvolvedInTransaction(thisTrans, data, wallet)) {
                        processedTransactions.add(thisTrans);
                    }
                    try
                    {
                        long blockNumber = Long.valueOf(thisTrans.blockNumber);
                        if (blockNumber > highestBlock)
                        {
                            highestBlock = blockNumber;
                        }
                    }
                    catch (NumberFormatException e)
                    {
                        //silent fail
                    }
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            if (highestBlock > 0) tokensService.tokenContractUpdated(token, highestBlock);
            return processedTransactions.toArray(new Transaction[processedTransactions.size()]);
        });
    }

    /**
     * Parse all transactions not associated with known tokens and pick up unknown contracts
     * @param transactions
     * @param tokensService
     * @return
     */
    public Observable<Transaction[]> processRemainingTransactions(Transaction[] transactions, TokensService tokensService)
    {
        return Observable.fromCallable(() -> {
            List<Transaction> processedTxList = new ArrayList<>();
            //process the remaining transactions
            for (Transaction t : transactions)
            {
                if (t.input != null)
                {
                    TransactionInput data = transactionDecoder.decodeInput(t.input);
                    if (t.isConstructor || (data != null && data.functionData != null))
                    {
                        Token localToken = tokensService.getToken(t.to);
                        if (localToken == null && !unknownContracts.contains(t.to)) unknownContracts.add(t.to);
                    }
                }
                processedTxList.add(t);
            }

            return processedTxList.toArray(new Transaction[processedTxList.size()]);
        });
    }

    public Observable<TokenInfo> addToken(String address)
    {
        return tokenRepository.update(address);
    }

    public Observable<TokenInfo[]> addTokens(List<String> addresses)
    {
        return tokenRepository.update(addresses.toArray(new String[addresses.size()]) ).toObservable();
    }

    public void setupUnknownList(TokensService tokensService, List<String> xmlContractAddresses)
    {
        unknownContracts.clear();
        if (xmlContractAddresses != null)
        {
            for (String address : xmlContractAddresses)
            {
                if (tokensService.getToken(address) == null) unknownContracts.add(address);
            }
        }
    }

    public List<String> getUnknownContracts(Transaction[] transactionList)
    {
        Log.d(TAG, "New unknown size: " + unknownContracts.size());
        return unknownContracts;
    }

    public Token terminateToken(Token token, Wallet wallet, NetworkInfo network)
    {
        tokenRepository.terminateToken(token, wallet, network);
        return token;
    }
}
