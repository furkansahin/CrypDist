
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.tools.javac.util.Pair;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by Kaan on 18-Feb-17.
 */

public class BlockchainManager extends Observable
{
    private final int BLOCK_SIZE = 4;
    private final int MAX_TIMEOUT_MS = 10000;
    private Blockchain blockchain;
    private PostgresDB dbManager;
    private ServerAccessor serverAccessor;
    private PriorityBlockingQueue<Transaction> transactionBucket;
    private ArrayList<Transaction> transactionBucket_solid;
    private boolean hashReceived;
    private String lastHash;
    private Long lastTime;
    // To collect hash values to given blockIds with time stamp
    // Mapping is like BlockId -> ArrayOf[(Hash, TimeStamp)]
    private ConcurrentHashMap<String, ArrayList<Pair>> hashes;
    private int numOfPairs;

    public BlockchainManager()
    {
        Block genesis = new Block();
        dbManager = new PostgresDB("blockchain", "postgres", "", false);
        blockchain = new Blockchain(genesis);
   //     serverAccessor = new ServerAccessor();
        transactionBucket = new PriorityBlockingQueue<>();
        transactionBucket_solid = new ArrayList<>(BLOCK_SIZE);
        hashes = new ConcurrentHashMap<>();
        numOfPairs = 0;
        Timer timer = new Timer();
        timer.schedule(new BlockchainBatch(),0, 5 * 1000);

    }

    public Blockchain getBlockchain()
    {
        return blockchain;
    }

    public void uploadFile(String filePath)
    {
        String[] path = filePath.split("/");
        String fileName = path[path.length - 1];

        Transaction upload = new Transaction(fileName, filePath);
        //upload.execute(serverAccessor);
        transactionBucket.add(upload);
        System.out.println("Transaction added, being broadcasted.");

        Gson gson = new Gson();
        broadcast(gson.toJson(upload), 1, null);

        System.out.println("Notified");

    }

    // Transaction is serializable and taken from the p2p connection as it is
    public void addTransaction(Transaction transaction)
    {
        transactionBucket.add(transaction);
    }
    public void addTransaction(String data)
    {
        Gson gson = new Gson();
        Transaction transaction = gson.fromJson(data, Transaction.class);
        transactionBucket.add(transaction);
    }

    private boolean addBlockToBlockchain(Block block) throws Exception {
        Gson gson = new Gson();
        if (blockchain.addBlock(block))
            if (dbManager.addBlock(block.getHash(), gson.toJson(block, Block.class)))
  //          if (dbManager.addBlock("furkan", "Sahin"))
                return true;

        try {
            throw new Exception("The block is added to the blockchain but the db could not be updated!");
        }
        catch (Exception ignored){}

        return false;
    }


    public void receiveHash(String data, Long timeStamp, String blockId) {
        hashReceived = true;
        lastHash = data;
        lastTime = timeStamp;
        if (!hashes.containsKey(blockId))
            hashes.put(blockId, new ArrayList<>());
        hashes.get(blockId).add(new Pair<>(lastHash, timeStamp));
    }

    public int getBlockchainLength()
    {
        return blockchain.getLength();
    }

//    public ArrayList<Block> getLongestChain()
//    {
//        ArrayList<String> hashChain = blockchain.getBlockchain();
//        ArrayList<Block> blocks = new ArrayList<Block>();
//
//        for (int i = 0; i < hashChain.size(); i++)
//            blocks.add(blockchain.getBlock(hashChain.get(i)));
//        return blocks;
//    }

    public void createBlock()
    {
        System.out.println("Block is being created");
        if (transactionBucket_solid.size() != BLOCK_SIZE)
        {
            return;
        }
        else
        {
            String prevHash = blockchain.getLastBlock();
            long timestamp = getTime();
            long maxNonce = Long.MAX_VALUE;

            String blockId = generateBlockId(transactionBucket_solid);
            String hash = mineBlock(blockId, prevHash, timestamp, maxNonce);

            hashReceived = false;

            Block block = null;
            try {
                System.out.println("Hash: " + hash);
                block = new Block(prevHash, timestamp, hash, transactionBucket_solid, blockchain);
                if (block == null)
                {
                    System.out.println("BLOCK COULD NOT BE CREATED");
                    return;
                }
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            try {
                addBlockToBlockchain(block);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private String generateBlockId(ArrayList<Transaction> transactionBucket_solid) {
        StringBuilder blockId = new StringBuilder();
        for (Transaction t : transactionBucket_solid)
            blockId.append(t.getStringFormat());
        return blockId.toString();
    }

    public long getTime()
    {
        return System.currentTimeMillis();
    }

    public boolean validateHash(String hash)
    {
        return blockchain.getLastBlock().equals(hash);
    }

    public String mineBlock(String blockId, String prevHash, long timestamp, long maxNonce)
    {
        ExecutorService executor = Executors.newCachedThreadPool();

        Callable<String> task =  new BlockMiner(blockId, prevHash, timestamp, maxNonce, transactionBucket_solid);
        Future<String> future = executor.submit(task);

        try {
            return future.get();
        } catch (Exception e) {
            return "";
        }
    }

    // Any message which is going to be broadcasted will be processed in here
    public Long broadcast(String data, int flag, String blockId)
    {

        long time = getTime();
        JsonObject obj = new JsonObject();
        obj.addProperty("flag",flag);
        obj.addProperty("data", data);
        obj.addProperty("timeStamp", time);
        if (flag == 2 )
            obj.addProperty("blockId", blockId);
        setChanged();
        notifyObservers(obj);
        return time;
    }

    /**
     * This class is used for mining a block which means finding a
     * convenient hash key before creating it. The hash key must
     * include zeros in its 8 most significant digit. The purpose is
     * to find a minimum value which is called nonce to make the hash
     * key. The score should be below a target after doing this.
     */
    private class BlockMiner implements Callable<String>
    {
        private MerkleTree data;
        private String prevHash;
        private long timestamp;
        private long maxNonce;
        private String blockId;

        public BlockMiner(String blockId, String prevHash, long timestamp, long maxNonce,
                          ArrayList<Transaction> transactions)
        {
            this.blockId = blockId;
            this.prevHash = prevHash;
            this.timestamp = timestamp;
            this.maxNonce = maxNonce;

            ArrayList<String> stringTransactions = new ArrayList<String>();
            for (int i = 0; i < transactions.size(); i++)
                stringTransactions.add(transactions.get(i).getStringFormat());
            data = new MerkleTree(stringTransactions);
        }

        public String call()
        {
            if(hashReceived) {
                return lastHash;
            }
            long score = Long.MAX_VALUE;
            long bestNonce = -1;
            byte[] hash = null;
            try
            {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                String blockData = "{" + timestamp + ":" + prevHash + ":" +
                                    data.getRoot();

                // Try all values as brute-force
                for (int i = 0; i < maxNonce; i++)
                {
                    if(hashReceived) {
                        return lastHash;
                    }
                    String dataWithNonce = blockData + ":" + i + "}";
                    hash = md.digest(dataWithNonce.getBytes("UTF-8"));

                    long tempScore = 0L;
                    for (int j = 0; j < 8; j++)
                        tempScore = (hash[j] & 0xff) + (tempScore << 8);

                    if (tempScore < score && tempScore > 0)
                        score = tempScore;

                    // Check if most significant 8 digits are zero
                    if ((hash[0] & 0xff) == 0x00)
                    {
                        bestNonce = i;
                        break;
                    }
                }
            }
            catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {}

            System.out.println("CALL TO NOTIFY OBSERVERS!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            long timeStamp = broadcast(new String(hash), 2, blockId);
            if (!hashes.containsKey(blockId))
            {
                hashes.put(blockId, new ArrayList<>());
            }
            hashes.get(blockId).add(new Pair<String, Long>(new String(hash), timeStamp));

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            String minHash = findMinHash(blockId);

            hashes.remove(blockId);
            return minHash;
        }

        private String findMinHash(String blockId)
        {
            long minTime = Long.MAX_VALUE;
            String minHash = "";
            for (Pair<String, Long> p : hashes.get(blockId)){
                if (p.snd < minTime)
                {
                    minTime = p.snd;
                    minHash = p.fst;
                }
            }
            return minHash;
        }
    }

    private class BlockchainBatch extends TimerTask{
        @Override
        public void run() {
            while(true) {
                if(!transactionBucket.isEmpty()) {
                    if (transactionBucket.peek().getTimeStamp().getTime() < getTime() - MAX_TIMEOUT_MS)
                    {
                        transactionBucket_solid.add(transactionBucket.poll());
                        if (transactionBucket_solid.size() == BLOCK_SIZE)
                        {
                            hashReceived = false;
                            createBlock();
                        }
                    }
                    else
                        break;
                }

            }
        }
    }
}
