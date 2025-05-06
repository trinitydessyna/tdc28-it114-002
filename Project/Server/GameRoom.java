package Project.Server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import Project.Common.Constants;
import Project.Common.LoggerUtil;
import Project.Common.Phase;
import Project.Common.TimedEvent;
import Project.Common.TimerType;
import Project.Exceptions.MissingCurrentPlayerException;
import Project.Exceptions.NotPlayersTurnException;
import Project.Exceptions.NotReadyException;
import Project.Exceptions.PhaseMismatchException;
import Project.Exceptions.PlayerNotFoundException;

public class GameRoom extends BaseGameRoom {

    // used for general rounds (usually phase-based turns)
    private TimedEvent roundTimer = null;

    // used for granular turn handling (usually turn-order turns)
    private TimedEvent turnTimer = null;
    private List<ServerThread> turnOrder = new ArrayList<>();
    private long currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
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
        long removedClient = sp.getClientId();
        turnOrder.removeIf(player -> player.getClientId() == sp.getClientId());
        if (clientsInRoom.isEmpty()) {
            resetReadyTimer();
            resetTurnTimer();
            resetRoundTimer();
            onSessionEnd();
        } else if (removedClient == currentTurnClientId) {
            onTurnStart();
        }
    }

    // timer handlers
    @SuppressWarnings("unused")
    private void startRoundTimer() {
        roundTimer = new TimedEvent(30, () -> onRoundEnd());
        roundTimer.setTickCallback((time) -> {
            System.out.println("Round Time: " + time);
            sendCurrentTime(TimerType.ROUND, time);
        });
    }

    private void resetRoundTimer() {
        if (roundTimer != null) {
            roundTimer.cancel();
            roundTimer = null;
            sendCurrentTime(TimerType.ROUND, -1);
        }
    }

    private void startTurnTimer() {
        turnTimer = new TimedEvent(30, () -> onTurnEnd());
        turnTimer.setTickCallback((time) -> {
            System.out.println("Turn Time: " + time);
            sendCurrentTime(TimerType.TURN, time);
        });
    }

    private void resetTurnTimer() {
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer = null;
            sendCurrentTime(TimerType.TURN, -1);
        }
    }
    // end timer handlers

    // lifecycle methods

    /** {@inheritDoc} */
    @Override
    protected void onSessionStart() {
        LoggerUtil.INSTANCE.info("onSessionStart() start");
        changePhase(Phase.IN_PROGRESS);
        currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
        setTurnOrder();
        round = 0;

        LoggerUtil.INSTANCE.info("onSessionStart() end");
        onRoundStart();
    }

    /** {@inheritDoc} */
    @Override
protected void onRoundStart() {
    LoggerUtil.INSTANCE.info("onRoundStart() start");
    round++;
    sendGameEvent("Round: " + round);

    // Reset player choices
    clientsInRoom.values().forEach(sp -> {
        LoggerUtil.INSTANCE.info("Resetting choice for player: " + sp.getPlayerId());
        sp.setChoice(null);
    });

    // Change phase to CHOOSING
    changePhase(Phase.CHOOSING);
    LoggerUtil.INSTANCE.info("Phase changed to: " + Phase.CHOOSING);

    // Optional delay for phase transition (if needed)
    try {
        Thread.sleep(1000); // Adjust time as needed
    } catch (InterruptedException e) {
        e.printStackTrace();
    }

    // Reset round timer
    resetRoundTimer();
    LoggerUtil.INSTANCE.info("Round timer reset for round " + round);

    // Reset turn status and start the turn
    resetTurnStatus();
    LoggerUtil.INSTANCE.info("Turn status reset for round " + round);

    onTurnStart();
    LoggerUtil.INSTANCE.info("Turn started for round " + round);

    // Start the round timer
    startRoundTimer();
    LoggerUtil.INSTANCE.info("Round timer started for round " + round);

    LoggerUtil.INSTANCE.info("onRoundStart() end");
}


    /** {@inheritDoc} */
    @Override
    protected void onTurnStart() {
        LoggerUtil.INSTANCE.info("onTurnStart() start");
        resetTurnTimer();
        ServerThread currentPlayer = null;
        try {
            currentPlayer = getNextPlayer();
            relay(null, String.format("It's %s's turn", currentPlayer.getDisplayName()));
        } catch (MissingCurrentPlayerException | PlayerNotFoundException e) {

            e.printStackTrace();
        }

        startTurnTimer();
        LoggerUtil.INSTANCE.info("onTurnStart() end");
    }

    // Note: logic between Turn Start and Turn End is typically handled via timers
    // and user interaction
    /** {@inheritDoc} */
    @Override
    protected void onTurnEnd() {
        LoggerUtil.INSTANCE.info("onTurnEnd() start");
        resetTurnTimer(); // reset timer if turn ended without the time expiring
        try {
            ServerThread currentPlayer = getCurrentPlayer();
            if (currentPlayer.getPoints() >= 3) {
                relay(null, String.format("%s has won the game!", currentPlayer.getDisplayName()));
                LoggerUtil.INSTANCE.info("onTurnEnd() end"); // added here for consistent lifecycle logs
                onSessionEnd();
                return;
            }
            // optionally can use checkAllTookTurn();
            if (isLastPlayer()) {
                // if the current player is the last player in the turn order, end the round
                onRoundEnd();
            } else {
                onTurnStart();
            }
        } catch (MissingCurrentPlayerException | PlayerNotFoundException e) {

            e.printStackTrace();
        }
        LoggerUtil.INSTANCE.info("onTurnEnd() end");
    }

    // Note: logic between Round Start and Round End is typically handled via timers
    // and user interaction
    /** {@inheritDoc} */
    @Override
    protected void onRoundEnd() {
        LoggerUtil.INSTANCE.info("onRoundEnd() start");
        resetRoundTimer(); // reset timer if round ended without the time expiring
        ProcessBattles();
        LoggerUtil.INSTANCE.info("onRoundEnd() end");
        // moved end condition check to onTurnEnd()
        ProcessBattles();
        onRoundStart();
    }

    /** {@inheritDoc} */
    @Override
    protected void onSessionEnd() {
        LoggerUtil.INSTANCE.info("onSessionEnd() start");
        turnOrder.clear();
        currentTurnClientId = Constants.DEFAULT_CLIENT_ID;

        List<ServerThread> survivors = turnOrder.stream()
                .filter(sp -> !sp.isEliminated())
                .collect(Collectors.toList());
        if (survivors.size()==1){
            ServerThread winner = survivors.get(0);
            relay(null, String.format("%s has won the game!", winner.getDisplayName()));
            endGame();
        }else if(survivors.isEmpty()){
            sendGameEvent("Game Over, Everyone is eliminated");
            endGame();
        }
        // reset any pending timers
        resetTurnTimer();
        resetRoundTimer();
        resetReadyStatus();
        resetTurnStatus();
        clientsInRoom.values().stream().forEach(s -> s.setPoints(0));
        changePhase(Phase.READY);
        LoggerUtil.INSTANCE.info("onSessionEnd() end");
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

    private void setTurnOrder() {
        turnOrder.clear();
        turnOrder = clientsInRoom.values().stream().filter(ServerThread::isReady).collect(Collectors.toList());
        Collections.shuffle(turnOrder);
    }

    private ServerThread getCurrentPlayer() throws MissingCurrentPlayerException, PlayerNotFoundException {
        // quick early exit
        if (currentTurnClientId == Constants.DEFAULT_CLIENT_ID) {
            throw new MissingCurrentPlayerException("Current Plaer not set");
        }
        return turnOrder.stream()
                .filter(sp -> sp.getClientId() == currentTurnClientId)
                .findFirst()
                // this shouldn't occur but is included as a "just in case"
                .orElseThrow(() -> new PlayerNotFoundException("Current player not found in turn order"));
    }

    private ServerThread getNextPlayer() throws MissingCurrentPlayerException, PlayerNotFoundException {
        int index = 0;
        if (currentTurnClientId != Constants.DEFAULT_CLIENT_ID) {
            index = turnOrder.indexOf(getCurrentPlayer()) + 1;
            if (index >= turnOrder.size()) {
                index = 0;
            }
        }
        ServerThread nextPlayer = turnOrder.get(index);
        currentTurnClientId = nextPlayer.getClientId();
        return nextPlayer;
    }

    private boolean isLastPlayer() throws MissingCurrentPlayerException, PlayerNotFoundException {
        // check if the current player is the last player in the turn order
        return turnOrder.indexOf(getCurrentPlayer()) == (turnOrder.size() - 1);
    }

    @SuppressWarnings("unused")
    private void checkAllTookTurn() {
        int numReady = clientsInRoom.values().stream()
                .filter(sp -> sp.isReady())
                .toList().size();
        int numTookTurn = clientsInRoom.values().stream()
                // ensure to verify the isReady part since it's against the original list
                .filter(sp -> sp.isReady() && sp.didTakeTurn())
                .toList().size();
        if (numReady == numTookTurn) {
            relay(null,
                    String.format("All players have taken their turn (%d/%d) ending the round", numTookTurn, numReady));
            onRoundEnd();
        }
    }

    // start check methods
    private void checkCurrentPlayer(long clientId) throws NotPlayersTurnException {
        if (currentTurnClientId != clientId) {
            throw new NotPlayersTurnException("You are not the current player");
        }
    }

    // end check methods

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
            checkCurrentPlayer(currentUser.getClientId());
            checkIsReady(currentUser);
            if (currentUser.didTakeTurn()) {
                currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "You have already taken your turn this round");
                return;
            }
            int points = new Random().nextInt(4) == 3 ? 1 : 0;
            sendGameEvent(String.format("%s %s", currentUser.getDisplayName(),
                    points > 0 ? "gained a point" : "didn't gain a point"));
            if (points > 0) {
                currentUser.changePoints(points);
                sendPlayerPoints(currentUser);
            }

            currentUser.setTookTurn(true);
            // TODO handle example text possibly or other turn related intention from client
            sendTurnStatus(currentUser, currentUser.didTakeTurn());

            onTurnEnd();
        } catch (NotPlayersTurnException e) {
            currentUser.sendMessage(Constants.DEFAULT_CLIENT_ID, "It's not your turn");
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (NotReadyException e) {
            // The check method already informs the currentUser
            LoggerUtil.INSTANCE.severe("handleTurnAction exception", e);
        } catch (PlayerNotFoundException e) {
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
    List<ServerThread> players = turnOrder;

    for (int i = 0; i < players.size(); i++) {
        ServerThread player1 = players.get(i);
        for (int j = i+1; j<players.size(); j++){
            ServerThread player2 = players.get(j);
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
            for (int k = j + 1; k < players.size(); k++) {
                ServerThread player3 = players.get(k);
                int result3 = new Random().nextInt(3); // 0: draw, 1: player1 wins, 2: player3 wins
                if (result3 == 1) {
                    player1.changePoints(1);
                    player3.setEliminated(true);
                    sendGameEvent(player1.getDisplayName() + " wins against " + player3.getDisplayName());
                } else if (result3 == 2) {
                    player3.changePoints(1);
                    player1.setEliminated(true);
                    sendGameEvent(player3.getDisplayName() + " wins against " + player1.getDisplayName());
                } else if (result3 == 3) {
                    player3.changePoints(1);
                    player2.setEliminated(true);
                    sendGameEvent(player3.getDisplayName() + " wins against " + player2.getDisplayName());
                } else if (result3 == 4) {
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
        players.forEach(player -> sendPlayerPoints(player));
        LoggerUtil.INSTANCE.info("ProcessBattles() end");
        String message = "Battle results have been processed.";
        sendGameEvent(message);
        }
            
    }
    private void endGame(){
        changePhase(Phase.ENDED); // Assuming changePhase is the intended method
        sendGameEvent("Game Over");

        List<ServerThread> scoreboard = new ArrayList<>(turnOrder);
        scoreboard.sort((p1, p2) -> Integer.compare(p2.getPoints(), p1.getPoints()));
        StringBuilder scoreboardList = new StringBuilder("Scoreboard:\n");
        for (ServerThread player : scoreboard) {
            scoreboardList.append(player.getDisplayName()).append(": ").append(player.getPoints()).append("\n");
        }
        sendGameEvent(scoreboardList.toString());

        for (ServerThread player: turnOrder){
            player.setChoice(null);
            player.setEliminated(false);
            player.setPoints(0);
            player.sendGameEvent("Now Resetting the game");
        }
        sendGameEvent("Game has ended, Want to play again?");
    }
}
    // end receive data from ServerThread (GameRoom specific)

