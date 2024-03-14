package bguspl.set.ex;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    //------------------------------Self Added Fields-----------------------------------

    /**
     * A queue of actions to be performed by the player.
     */
    private BlockingQueue<Integer> actionsQueue;

    
    /**
     * The slots that the player has placed tokens on.
     */
    private ArrayList<Integer> tokenSlots;
    
    /**
     * The dealer of the game.
     */
    private Dealer dealer;
    
    /**
     * Represents the legality of the current set the player has his tokens on.
     * -1 means no set/set wasn't checked, 0 means illegal set, and 1 means legal set.
     */
    private volatile int isSetLegal;
    private final int NO_SET = -1;
    private final int LEGAL_SET = 1;
    private final int ILLEGAL_SET = 0; 

    /**
     * Represents the penalty status of the player.
     */
    private volatile AtomicBoolean frozen;

    private final int ONE_SECOND = 1000;
    //----------------------------------------------------------------------------------

    

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        score = 0;
        isSetLegal = NO_SET;
        frozen = new AtomicBoolean(false);
        actionsQueue = new ArrayBlockingQueue<>(env.config.featureSize); // the size of the queue is the number of features
        tokenSlots = new ArrayList<>();
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            try{
                Integer pressedSlot = actionsQueue.take();
                if(pressedSlot != null) {
                    processAction(pressedSlot);
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                int randomSlot = (int) (Math.random() * env.config.tableSize);
                keyPressed(randomSlot);
                // try {
                //     synchronized (this) { wait(); }
                // } catch (InterruptedException ignored) {}
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        actionsQueue.clear();
        if(!human && aiThread != null) {
            aiThread.interrupt();
        }
        if(playerThread != null) {
            playerThread.interrupt();
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(!terminate && table.slotToCard[slot] != null && !frozen.get() && !table.tableLock.get()) {
            try {
                actionsQueue.put(slot);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        while(!terminate && !setFrozen(true)) {}
        long secondsToFreeze = env.config.pointFreezeMillis/ONE_SECOND;
        for(int i = 0; i < secondsToFreeze; i++) {
            env.ui.setFreeze(id, (secondsToFreeze - i)*ONE_SECOND);
            try {
                Thread.sleep(ONE_SECOND);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        env.ui.setFreeze(id, 0);
        isSetLegal = NO_SET;
        score++;
        env.ui.setScore(id, score);
        tokenSlots.clear();
        while(!terminate && !setFrozen(false)) {}

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests   
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        while(!terminate && !setFrozen(true)) {}
        try {
            for(int i = 0; i < env.config.penaltyFreezeMillis / ONE_SECOND; i++){
                env.ui.setFreeze(id, env.config.penaltyFreezeMillis - (i * ONE_SECOND));
                Thread.sleep(ONE_SECOND);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        env.ui.setFreeze(id, 0);
        isSetLegal = NO_SET;
        while(!terminate && !setFrozen(false)) {}
    }

    public int score() {
        return score;
    }

    private void processAction(Integer slot) {
        if(!table.removeToken(id, slot) && tokenSlots.size () < env.config.featureSize) {
            table.placeToken(id, slot);
            tokenSlots.add(slot);
        }
        else {tokenSlots.remove(slot);}

        //Check if the player has enough tokens to form a set:
        if(tokenSlots.size() == env.config.featureSize) {
            int[] setAndId = null;
            while(!terminate && setAndId == null) {
                setAndId = getSetAndId();
            }
            //Notify the dealer that the player has a set, and wait for it to be checked:
            synchronized (dealer) {
                dealer.enqueueSet(setAndId);
                dealer.notifyAll();
                while(!terminate && isSetLegal == NO_SET) {
                    try {
                        dealer.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            //If the set is legal, the player gets a point, otherwise he gets a penalty:
            if(isSetLegal == LEGAL_SET) {
                point();
            }
            else if(isSetLegal == ILLEGAL_SET) {
                penalty();
            }
        }
        actionsQueue.clear();
    }
    

    /**
     * Removes the specified token from the player's token slots.
     *
     * @param slot the slot number of the token to be removed
     * @return true if the token was successfully removed, false otherwise
     */
    public boolean removeToken(int slot) {
        return tokenSlots.remove((Integer) slot);
    }

    /**
     * @return the ID of the player
     */
    public int getId() {
        return id;
    }

    /**
     * Changes the status of the set legality.
     *
     * @param newStatus the new status of the set legality
     */
    protected void changeSetLegal(boolean newStatus) {
        if(newStatus) {
            isSetLegal = LEGAL_SET;
        }
        else {
            isSetLegal = ILLEGAL_SET;
        }
    }

    /**
     * Retrieves the set of cards represented by the player's token slots.
     * If the number of token slots is less than the configured feature size, returns null.
     *
     * @return The set of cards represented by the player's token slots, or null if the number of token slots is too small.
     */
    public int[] getSet() {
        if(tokenSlots.size() != env.config.featureSize) {
            return null;
        }
        int[] set = new int[env.config.featureSize];
        for(int i = 0; i < env.config.featureSize; i++) {
            set[i] = table.slotToCard[tokenSlots.get(i)];
        }
        return set;
    }

    /**
     * Checks if the given set of cards matches the current state of the player's table and token slots.
     * 
     * @param set an array representing a set of cards
     * @return true if the set matches the player's table, false otherwise
     */
    public boolean guaranteeSet(int[] set) {
        for(int i = 0; i < env.config.featureSize; i++) {
            if(tokenSlots.size() != env.config.featureSize || table.slotToCard[tokenSlots.get(i)] != set[i] || tokenSlots.get(i) != table.cardToSlot[set[i]]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sets the frozen status of the player.
     *
     * @param newStatus the new frozen status to set
     * @return true if the frozen status was successfully set, false otherwise
     */
    public boolean setFrozen(boolean newStatus) {
        return frozen.compareAndSet(!newStatus, newStatus);
    }

    /**
     * Returns the list of token slots for the player.
     *
     * @return the list of token slots
     */
    public ArrayList<Integer> getTokenSlots() {
        return tokenSlots;
    }

    public void removeAllTokens() {
        for(int slot : tokenSlots) {
            table.removeToken(id, slot);
        }
        tokenSlots.clear();
    }

    /**
     * Returns an array containing the set and id of the player.
     * The id will be the first element of the array.
     *
     * @return an array containing the set and id of the player at index 0, or null if there is no set.
     */
    private int[] getSetAndId() {
        try{
            if(tokenSlots.size() != env.config.featureSize) {
                return null;
            }
            int[] output = new int[env.config.featureSize + 1];
            output[0] = id; //Id will be the first element of the array
            for(int i = 0; i < env.config.featureSize; i++) {
                output[i + 1] = table.slotToCard[tokenSlots.get(i)];
            }
            return output;
        } catch (Exception e) {return null;}

    }

    protected Thread getThread() {
        return playerThread;
    }
}
