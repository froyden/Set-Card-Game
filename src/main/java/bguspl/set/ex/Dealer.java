package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    //------------------------------Self Added Fields-----------------------------------

    /**
     * The blocking queue that holds the players that found a set.
     */
    private BlockingQueue<int[]> setQueue;

    /**
     * A semaphore that is used to control the access to the setQueue.
     */
    protected Semaphore semaphore;

    /**
     * A number that is used to fix the timer.
     */
    private final int TIMER_FIX = 999;

    private volatile int numOfMissingCards;

    private long ONE_SECOND = 1000;

    private long TEN_MILLIS = 10;

    private final Thread[] playerThreads;

    //----------------------------------------------------------------------------------

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        terminate = false;
        setQueue = new LinkedBlockingQueue<>();
        semaphore = new Semaphore(env.config.players , true); //Creates a fairness semaphore
        numOfMissingCards = env.config.tableSize;
        playerThreads = new Thread[players.length];
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < players.length; i++) {
            playerThreads[i] = new Thread(players[i] , "Player-" + i);
            playerThreads[i].start();
        }

        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }

        if(!terminate) {
            announceWinners();
            terminate();
            try {
                Thread.sleep(env.config.endGamePauseMillies);
            } catch (InterruptedException e) {}
            
        }

        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        updateTimerDisplay(true);
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    // public void terminate() {
    //     if(!terminate) {
    //         setQueue.clear();
    //         terminate = true;
    //         for(int i=env.config.players-1; i >= 0; i--) { // Terminate the players in reverse order
    //             players[i].terminate();
    //             try {
    //                 players[i].getThread().join();
    //             } catch (InterruptedException e) {}
    //         }
    //     }
    // }

    public void terminate() {
        if(true) {
            setQueue.clear();
            terminate = true;
            //Terminate the players in reverse order
            for(int i=env.config.players-1; i >= 0; i--) {
                players[i].terminate();
            }

            for(int i=env.config.players-1; i >= 0; i--) {
                while (true) {
                    try {
                        players[i].getThread().join();
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        try {
            if(!setQueue.isEmpty()) {
                int[] setAndId = setQueue.take();
                if(setAndId == null) {
                    return;
                }
                Player currentPlayer = players[setAndId[0]];
                currentPlayer.setFrozen(true); // Freeze the player so he can't keep sending sets
                
                int[] set = {setAndId[1], setAndId[2], setAndId[3]};
                if(set != null) {
                    boolean isLegal = env.util.testSet(set);
                    if(isLegal && currentPlayer.guaranteeSet(set)) {
                        
                        table.changeTableLock(true); // Lock the table so no player can change it
                        // Tells the player that the set is legal, and wakes him up
                        synchronized(this) {
                            currentPlayer.changeSetLegal(true);
                            notifyAll();
                        }
                        // Remove the cards from the table, and the tokens on the deleted slots from the players
                        
                        for(Integer cardToDelete : set) {
                            int slotToDelete = table.cardToSlot[cardToDelete];
                            table.removeCard(slotToDelete);
                            numOfMissingCards++;
                            for(Player p : players) {
                                p.removeToken(slotToDelete);
                            }
                        }
                        table.changeTableLock(false);
                        updateTimerDisplay(true); // Reset the timer because a set was found
                    }
                    else {
                        // Tells the player that the set is illegal, and wakes him up
                        synchronized(this) {
                            currentPlayer.changeSetLegal(false);
                            notifyAll();
                        }
                    }
                }
                currentPlayer.setFrozen(false);
            }           
        } catch (InterruptedException e) {return;}
        updateTimerDisplay(false);
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if(!deck.isEmpty() && numOfMissingCards > 0) {
            table.changeTableLock(true); // Unlocks the table again
            for(int i = 0; i < env.config.tableSize && !deck.isEmpty() && numOfMissingCards > 0; i++) {
                if(table.slotToCard[i] == null) {
                    table.placeCard(deck.remove(0), i);
                    numOfMissingCards--;
                }
            }
            // If hints are turned on, prints them to the console
            if(env.config.hints) {
                System.out.println("-------------------------------"); 
                table.hints(); 
            }
            table.changeTableLock(false); // Unlocks the table again
            updateTimerDisplay(false);
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
        if(setQueue.isEmpty()) {
            try{
                boolean warn = reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis + TIMER_FIX;
                if(warn) {
                    wait(TEN_MILLIS);
                }
                else{
                    wait(ONE_SECOND);
                }
            } catch (InterruptedException e) {return;}
        }
            
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + TIMER_FIX;
        }
        long timeLeft = reshuffleTime - System.currentTimeMillis();
        if(timeLeft < 0) {
            timeLeft = 0;
        }
        boolean warn = timeLeft <= env.config.turnTimeoutWarningMillis;
        env.ui.setCountdown(timeLeft, warn);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        table.changeTableLock(true);
        for(int i = 0; i < env.config.tableSize; i++) {
            Integer card = table.slotToCard[i];
            if(card != null) {
                table.removeCard(i);
                numOfMissingCards++;
                for(Player player : players) {
                    player.removeAllTokens();
                }
                deck.add(card);
            }
        }
        table.changeTableLock(false);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int highestScore = 0;
        for(Player player : players) {
            int score = player.score();
            if(score > highestScore) {
                highestScore = score;
            }
        }
        ArrayList<Integer> winners = new ArrayList<>();
        for(Player player : players) {
            if(player.score() == highestScore) {
                winners.add(player.getId());
            }
        }
        // Create an array of the winning players' IDs
        int[] winnersArray = winners.stream().mapToInt(Integer::intValue).toArray();
        env.ui.announceWinner(winnersArray);
    }

    public void enqueueSet(int[] setAndId) {
        try {
            semaphore.acquire();
            setQueue.put(setAndId);
            semaphore.release();
        } catch (InterruptedException e) {return;}
    }

}
