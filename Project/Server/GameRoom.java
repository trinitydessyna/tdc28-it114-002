package Project.Server;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import Project.Common.Constants;
import Project.Common.LoggerUtil;
import Project.Common.Phase;
import Project.Common.TimedEvent;
import Project.Exceptions.NotReadyException;
import Project.Exceptions.PhaseMismatchException;
import Project.Exceptions.PlayerNotFoundException;

public class GameRoom extends BaseGameRoom {

    // used for general rounds (usually phase-based turns)
    private TimedEvent roundTimer = null;

    // used for granular turn handling (usually turn-order turns)
    private TimedEvent turnTimer = null;

    private int round = 0;
    public GameRoom(String name) {
        super(name);
    }

    /** {@inheritDoc} */
    @Override
    protected void onClientAdded(ServerThread sp) {
        // sync GameRoom state to new client
        syncCurrentPhase(sp);
        syncReadyStatus(sp);
        syncTurnStatus(sp);
        syncPlayerPoints(sp);
    }

    /** {@inheritDoc} */
    @Override
    protected void onClientRemoved(ServerThread sp) {
        // added after Summer 2024 Demo
        // Stops the timers so room can clean up
        LoggerUtil.INSTANCE.info("Player Removed, remaining: " + clientsInRoom.size());
        if (clientsInRoom.isEmpty()) {
            resetReadyTimer();
            resetTurnTimer();
            resetRoundTimer();
            onSessionEnd();
        }
    }

    // timer handlers
    private void startRoundTimer() {
        roundTimer = new TimedEvent(30, () -> onRoundEnd());
        roundTimer.setTickCallback((time) -> System.out.println("Round Time: " + time));
    }

    private void resetRoundTimer() {
        if (roundTimer != null) {
            roundTimer.cancel();
            roundTimer = null;
        }
    }

    private void startTurnTimer() {
        turnTimer = new TimedEvent(30, () -> onTurnEnd());
        turnTimer.setTickCallback((time) -> System.out.println("Turn Time: " + time));
    }

    private void resetTurnTimer() {
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer = null;
        }
    }
    // end timer handlers

    // lifecycle methods

    /** {@inheritDoc} */
    @Override
    protected void onSessionStart() {
        LoggerUtil.INSTANCE.info("onSessionStart() start");
        changePhase(Phase.IN_PROGRESS);
        round = 0;
        LoggerUtil.INSTANCE.info("onSessionStart() end");
        onRoundStart();
    }

    /** {@inheritDoc} */
    @Override
    protected void onRoundStart() {
        LoggerUtil.INSTANCE.info("onRoundStart() start");
        resetRoundTimer();
        resetTurnStatus();
        round++;
        relay(null, String.format("Round %d has started", round));
        startRoundTimer();
        LoggerUtil.INSTANCE.info("onRoundStart() end");
    }

    /** {@inheritDoc} */
    @Override
    protected void onTurnStart() {
        LoggerUtil.INSTANCE.info("onTurnStart() start");
        resetTurnTimer();
        startTurnTimer();
        handlePICK(null, LOBBY);
        LoggerUtil.INSTANCE.info("onTurnStart() end");
    }

    // Note: logic between Turn Start and Turn End is typically handled via timers
    // and user interaction
    /** {@inheritDoc} */
    @Override
    protected void onTurnEnd() {
        LoggerUtil.INSTANCE.info("onTurnEnd() start");
        resetTurnTimer(); // reset timer if turn ended without the time expiring

        LoggerUtil.INSTANCE.info("onTurnEnd() end");
    }

    // Note: logic between Round Start and Round End is typically handled via timers
    // and user interaction
    /** {@inheritDoc} */
    @Override
    protected void onRoundEnd() {
        LoggerUtil.INSTANCE.info("onRoundEnd() start");
        resetRoundTimer(); // reset timer if round ended without the time expiring

        LoggerUtil.INSTANCE.info("onRoundEnd() end");
        if (round >= 3) {
            onSessionEnd();
            ProcessBattles();
        }
        else{
            onRoundStart();
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onSessionEnd() {
        LoggerUtil.INSTANCE.info("onSessionEnd() start");
        resetReadyStatus();
        resetTurnStatus();
        changePhase(Phase.READY);
        LoggerUtil.INSTANCE.info("onSessionEnd() end");
        endGame(); // Call endGame when the session ends
    }
    // end lifecycle methods

    // send/sync data to ServerUser(s)
     private void syncPlayerPoints(ServerThread incomingClient) {
        clientsInRoom.values().forEach(serverUser -> {
            if (serverUser.getClientId() != incomingClient.getClientId()) {
                boolean failedToSync = !incomingClient.sendPlayerPoints(serverUser.getClientId(),
                        serverUser.getPoints());
                if (failedToSync) {
                    LoggerUtil.INSTANCE.warning(
                            String.format("Removing disconnected %s from list", serverUser.getDisplayName()));
                    disconnect(serverUser);
                }
            }
        });
    }

    private void sendPlayerPoints(ServerThread sp) {
        clientsInRoom.values().removeIf(spInRoom -> {
            boolean failedToSend = !spInRoom.sendPlayerPoints(sp.getClientId(), sp.getPoints());
            if (failedToSend) {
                removeClient(spInRoom);
            }
            return failedToSend;
        });
    }

    private void sendGameEvent(String str) {
        sendGameEvent(str, null);
    }

    private void sendGameEvent(String str, List<Long> targets) {
        clientsInRoom.values().removeIf(spInRoom -> {
            boolean canSend = false;
            if (targets != null) {
                if (targets.contains(spInRoom.getClientId())) {
                    canSend = true;
                }
            } else {
                canSend = true;
            }
            if (canSend) {
                boolean failedToSend = !spInRoom.sendGameEvent(str);
                if (failedToSend) {
                    removeClient(spInRoom);
                }
                return failedToSend;
            }
            return false;
        });
    }

    private void sendResetTurnStatus() {
        clientsInRoom.values().forEach(spInRoom -> {
            boolean failedToSend = !spInRoom.sendResetTurnStatus();
            if (failedToSend) {
                removeClient(spInRoom);
            }
        });
    }

    private void sendTurnStatus(ServerThread client, boolean tookTurn) {
        clientsInRoom.values().removeIf(spInRoom -> {
            boolean failedToSend = !spInRoom.sendTurnStatus(client.getClientId(), client.didTakeTurn());
            if (failedToSend) {
                removeClient(spInRoom);
            }
            return failedToSend;
        });
    }

    private void syncTurnStatus(ServerThread incomingClient) {
        clientsInRoom.values().forEach(serverUser -> {
            if (serverUser.getClientId() != incomingClient.getClientId()) {
                boolean failedToSync = !incomingClient.sendTurnStatus(serverUser.getClientId(),
                        serverUser.didTakeTurn(), true);
                if (failedToSync) {
                    LoggerUtil.INSTANCE.warning(
                            String.format("Removing disconnected %s from list", serverUser.getDisplayName()));
                    disconnect(serverUser);
                }
            }
        });
    }

    // end send data to ServerThread(s)

    // misc methods
    private void resetTurnStatus() {
        clientsInRoom.values().forEach(sp -> {
            sp.setTookTurn(false);
        });
        sendResetTurnStatus();
    }

    private void checkAllTookTurn() {
        int numReady = clientsInRoom.values().stream()
                .filter(sp -> sp.isReady())
                .toList().size();
        int numTookTurn = clientsInRoom.values().stream()
                // ensure to verify the isReady part since it's against the original list
                .filter(sp -> sp.isReady() && sp.didTakeTurn())
                .toList().size();
        if (numReady == numTookTurn) {
                    relay(null, String.format("All players have picked (%d/%d). Processing results...", numTookTurn, numReady));
                    ProcessBattles();
                    onRoundEnd();
                }
                
    }

    // receive data from ServerThread (GameRoom specific)

    /**
     * Example turn action
     * 
     * @param currentUser
     */
    protected void handleTurnAction(ServerThread currentUser, String exampleText) {
        // check if the client is in the room
        try {
            checkPlayerInRoom(currentUser);
            checkCurrentPhase(currentUser, Phase.IN_PROGRESS);
            checkIsReady(currentUser);
            if (currentUser.didTakeTurn()) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "You have already taken your turn this round");
                return;
            }
            currentUser.setTookTurn(true);
            // TODO handle example text possibly or other turn related intention from client
            sendTurnStatus(currentUser, currentUser.didTakeTurn());
            checkAllTookTurn();
        }
        catch(NotReadyException e){
            // The check method already informs the currentUser
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } 
        catch (PlayerNotFoundException e) {
            currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "You must be in a GameRoom to do the ready check");
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (PhaseMismatchException e) {
            currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID,
                    "You can only take a turn during the IN_PROGRESS phase");
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        }
    }
private void ProcessBattles(){
    LoggerUtil.INSTANCE.info("ProcessBattles() start");

    List<ServerThread> readyPlayers = clientsInRoom.values().stream()
    .filter(ServerThread::isReady).collect(Collectors.toList());

    List<ServerThread> toEliminate = readyPlayers.stream()
    .filter(p->p.getChoice()==null).collect(Collectors.toList());

    List<ServerThread> battlers = readyPlayers.stream().filter(p->p.isReady()
     //&& !isEliminated()
     //&& !p.isAway()
     //&& !p.isSpectator()
     && p.getChoice() != null).collect(Collectors.toList());
    for (int i = 0; i < battlers.size(); i++) {
        ServerThread player1 = battlers.get(i);
        for (int j = i+1; j<battlers.size(); j++){
            ServerThread player2 = battlers.get(j);
        // Simulate a battle between player1 and player2
            int result = new Random().nextInt(3); // 0: draw, 1: player1 wins, 2: player2 wins

            
         if (result == 1) {
            player1.changePoints(1);
            player2.setEliminated(true);
            sendGameEvent(player1.getDisplayName() + " wins against " + player2.getDisplayName());
        } else if (result == 2) {
            player2.changePoints(1);
            player1.setEliminated(true);
            sendGameEvent(player2.getDisplayName() + " wins against " + player1.getDisplayName());
        } else {
            for (int k = j + 1; k < battlers.size(); k++) {
                ServerThread player3 = battlers.get(k);
                int result3 = new Random().nextInt(3); // 0: draw, 1: player1 wins, 2: player3 wins
                if (result3 == 1) {
                    player1.changePoints(1);
                    player3.setEliminated(true);
                    sendGameEvent(player1.getDisplayName() + " wins against " + player3.getDisplayName());
                } else if (result3 == 2) {
                    player3.changePoints(1);
                    player1.setEliminated(true);
                    sendGameEvent(player3.getDisplayName() + " wins against " + player1.getDisplayName());
                } else if (result3 == 1) {
                    player3.changePoints(1);
                    player2.setEliminated(true);
                    sendGameEvent(player3.getDisplayName() + " wins against " + player2.getDisplayName());
                } else if (result3 == 2) {
                    player2.changePoints(1);
                    player3.setEliminated(true);
                    sendGameEvent(player2.getDisplayName() + " wins against " + player3.getDisplayName());
                }else {
                    sendGameEvent("Battle between " + player1.getDisplayName() + ", " + player2.getDisplayName() + " and " + player3.getDisplayName() + " is a draw");
                }
            }
        }
        }
        // Update all players with their new points
        LoggerUtil.INSTANCE.info("ProcessBattles() end");
        String message = "Battle results have been processed.";
        sendGameEvent(message);
        endGame(); // Call endGame after processing battles
        }
        }
            
    
    private void endGame(){
        changePhase(Phase.ENDED); // Assuming changePhase is the intended method
        sendGameEvent("Game Over");

        List<ServerThread> scoreboard = new ArrayList<>(clientsInRoom.values());
        scoreboard.sort((p1, p2) -> Integer.compare(p2.getPoints(), p1.getPoints()));
        StringBuilder scoreboardList = new StringBuilder("Scoreboard:\n");
        for (ServerThread player : scoreboard) {
            scoreboardList.append(player.getDisplayName()).append(": ").append(player.getPoints()).append("\n");
        }
        sendGameEvent(scoreboardList.toString());

        for (ServerThread player : clientsInRoom.values()) {
            player.setChoice(null);
            player.setEliminated(false);
            player.setPoints(0);
            player.sendGameEvent("Now Resetting the game");
        }
        sendGameEvent("Game has ended, Want to play again?");
    }

    protected void handlePICK(ServerThread sp, String message) {
        try {
            checkPlayerInRoom(sp);
            checkCurrentPhase(sp, Phase.IN_PROGRESS);
            checkIsReady(sp);
    
            if (sp.didTakeTurn()) {
                sp.sendMessage(Constants.DEFAULT_CLIENT_ID, "You have already picked.");
                return;
            }
    
            String choice = message.trim().toLowerCase();
            if (!choice.equals("r") && !choice.equals("p") && !choice.equals("s")) {
                sp.sendMessage(Constants.DEFAULT_CLIENT_ID, "Invalid choice. Pick R, P, or S.");
                return;
            }
    
            sp.setChoice(choice); // Assuming ServerThread has setChoice(String) method
            sp.setTookTurn(true);
    
            relay(null, sp.getDisplayName() + " has made their pick.");
            sendTurnStatus(sp, true);
    
            checkAllTookTurn();
    
        } catch (NotReadyException | PlayerNotFoundException | PhaseMismatchException e) {
            sp.sendMessage(Constants.DEFAULT_CLIENT_ID, e.getMessage());
            LoggerUtil.INSTANCE.severe("handlePICK exception", e);
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Unexpected error in handlePICK", e);
        }
    }
    


        // end receive data from ServerThread (GameRoom specific)
    }
