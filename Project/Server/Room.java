package Project.Server;

import java.util.concurrent.ConcurrentHashMap;

import Project.Common.Constants;
import Project.Common.LoggerUtil;
import Project.Common.RoomAction;
import Project.Common.TextFX;
import Project.Common.TextFX.Color;
import Project.Exceptions.DuplicateRoomException;
import Project.Exceptions.RoomNotFoundException;

public class Room implements AutoCloseable {
    private final String name;// unique name of the Room
    private volatile boolean isRunning = false;
    protected final ConcurrentHashMap<Long, ServerThread> clientsInRoom = new ConcurrentHashMap<Long, ServerThread>();

    public final static String LOBBY = "lobby";

    private void info(String message) {
        LoggerUtil.INSTANCE.info(TextFX.colorize(String.format("Room[%s]: %s", name, message), Color.PURPLE));
    }

    public Room(String name) {
        this.name = name;
        isRunning = true;
        info("Created");
    }

    public String getName() {
        return this.name;
    }

    protected boolean isRunning() {
        return isRunning;
    }

    protected synchronized void addClient(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        if (clientsInRoom.containsKey(client.getClientId())) {
            info("Attempting to add a client that already exists in the room");
            return;
        }
        clientsInRoom.put(client.getClientId(), client);
        client.setCurrentRoom(this);
        client.sendResetUserList();
        syncExistingClients(client);
        // notify clients of someone joining
        joinStatusRelay(client, true);

    }

    protected synchronized void removeClient(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        if (!clientsInRoom.containsKey(client.getClientId())) {
            info("Attempting to remove a client that doesn't exist in the room");
            return;
        }
        ServerThread removedClient = clientsInRoom.get(client.getClientId());
        if (removedClient != null) {
            // notify clients of someone joining
            joinStatusRelay(removedClient, false);
            clientsInRoom.remove(client.getClientId());
            autoCleanup();
        }
    }

    private void syncExistingClients(ServerThread incomingClient) {
        clientsInRoom.values().forEach(serverThread -> {
            if (serverThread.getClientId() != incomingClient.getClientId()) {
                boolean failedToSync = !incomingClient.sendClientInfo(
                        serverThread.getClientId(),
                        serverThread.getClientName(),
                        getName(), // room name
                        RoomAction.JOIN,
                        true);
                if (failedToSync) {
                    LoggerUtil.INSTANCE.warning(
                            String.format("Removing disconnected %s from list", serverThread.getDisplayName()));
                    disconnect(serverThread);
                }
            }
        });
    }

    private void joinStatusRelay(ServerThread client, boolean didJoin) {
        clientsInRoom.values().removeIf(serverThread -> {

            // Share info of the client joining or leaving the room
            boolean failedToSync = !serverThread.sendClientInfo(
                    client.getClientId(), // client id
                    client.getClientName(), // client name
                    getName(), // room name
                    didJoin ? RoomAction.JOIN : RoomAction.LEAVE // action
            );
            if (failedToSync) {
                LoggerUtil.INSTANCE.warning(
                        String.format("Removing disconnected %s from list", serverThread.getDisplayName()));
                disconnect(serverThread);
            }
            return failedToSync;
        });
    }

    /**
     * Sends a basic String message from the sender to all connectedClients
     * Note: Clients that fail to receive a message get removed from
     * connectedClients.
     * Adding the synchronized keyword ensures that only one thread can execute
     * these methods at a time,
     * preventing concurrent modification issues and ensuring thread safety
     * 
     * @param message
     * @param sender  ServerThread (client) sending the message or null if it's a
     *                server-generated message
     */
    protected synchronized void relay(ServerThread sender, String message) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }

        final long senderId = sender == null ? Constants.DEFAULT_CLIENT_ID : sender.getClientId();
        // Note: formattedMessage must be final (or effectively final) since outside
        // scope can't be changed inside a callback function (see removeIf() below)
        // Note: Changed in Milestone 3 since client will use its own knownClients list
        // to lookup the name
        final String formattedMessage = message;

        // loop over clients and send out the message; remove client if message failed
        // to be sent
        // Note: this uses a lambda expression for each item in the values() collection,
        // it's one way we can safely remove items during iteration
        info(String.format("sending message to %s recipients: %s", clientsInRoom.size(), formattedMessage));

        clientsInRoom.values().removeIf(serverThread -> {
            boolean failedToSend = !serverThread.sendMessage(senderId, formattedMessage);
            if (failedToSend) {
                LoggerUtil.INSTANCE.warning(
                        String.format("Removing disconnected %s from list", serverThread.getDisplayName()));
                disconnect(serverThread);
            }
            return failedToSend;
        });
    }

    /**
     * Takes a ServerThread and removes them from the Server
     * Adding the synchronized keyword ensures that only one thread can execute
     * these methods at a time,
     * preventing concurrent modification issues and ensuring thread safety
     * 
     * @param client
     */
    protected synchronized void disconnect(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        ServerThread disconnectingServerThread = clientsInRoom.remove(client.getClientId());
        if (disconnectingServerThread != null) {

            clientsInRoom.values().removeIf(serverThread -> {
                if (serverThread.getClientId() == disconnectingServerThread.getClientId()) {
                    return true;
                }
                boolean failedToSend = !serverThread.sendDisconnect(
                        disconnectingServerThread.getClientId());
                if (failedToSend) {
                    LoggerUtil.INSTANCE.warning(
                            String.format("Removing disconnected %s from list", serverThread.getDisplayName()));
                    disconnect(serverThread);
                }
                return failedToSend;
            });
            // relay(null, disconnectingServerThread.getDisplayName() + " disconnected");
            disconnectingServerThread.sendDisconnect(
                    disconnectingServerThread.getClientId());
            disconnectingServerThread.disconnect();
        }
        autoCleanup();
    }

    protected synchronized void disconnectAll() {
        info("Disconnect All triggered");
        if (!isRunning) {
            return;
        }
        clientsInRoom.values().removeIf(client -> {
            disconnect(client);
            return true;
        });
        info("Disconnect All finished");
    }

    /**
     * Attempts to close the room to free up resources if it's empty
     */
    private void autoCleanup() {
        if (!Room.LOBBY.equalsIgnoreCase(name) && clientsInRoom.isEmpty()) {
            close();
        }
    }

    @Override
    public void close() {
        // attempt to gracefully close and migrate clients
        if (!clientsInRoom.isEmpty()) {
            relay(null, "Room is shutting down, migrating to lobby");
            info(String.format("migrating %s clients", clientsInRoom.size()));
            clientsInRoom.values().removeIf(client -> {
                try {
                    Server.INSTANCE.joinRoom(Room.LOBBY, client);
                } catch (RoomNotFoundException e) {
                    e.printStackTrace();
                    // TODO, fill in, this shouldn't happen though
                }
                return true;
            });
        }
        Server.INSTANCE.removeRoom(this);
        isRunning = false;
        clientsInRoom.clear();
        info(String.format("closed"));
    }

    // start handle methods
    protected void handleListRooms(ServerThread sender, String roomQuery) {
        sender.sendRooms(Server.INSTANCE.listRooms(roomQuery));
    }

    public void handleCreateRoom(ServerThread sender, String roomName) {
        try {
            Server.INSTANCE.createRoom(roomName);
            Server.INSTANCE.joinRoom(roomName, sender);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Constants.DEFAULT_CLIENT_ID, "Room name cannot be empty");
        } catch (RoomNotFoundException e) {
            info("Room wasn't found (this shouldn't happen)");
            e.printStackTrace();
        } catch (DuplicateRoomException e) {
            sender.sendMessage(Constants.DEFAULT_CLIENT_ID, String.format("Room %s already exists", roomName));
        }
    }

    public void handleJoinRoom(ServerThread sender, String roomName) {
        try {
            Server.INSTANCE.joinRoom(roomName, sender);
        } catch (RoomNotFoundException e) {
            sender.sendMessage(Constants.DEFAULT_CLIENT_ID, String.format("Room %s doesn't exist", roomName));
        }
    }

    protected synchronized void handleDisconnect(BaseServerThread sender) {
        handleDisconnect((ServerThread) sender);
    }

    /**
     * Expose access to the disconnect action
     * 
     * @param serverThread
     */
    protected synchronized void handleDisconnect(ServerThread sender) {
        disconnect(sender);
    }

    protected synchronized void handleReverseText(ServerThread sender, String text) {
        StringBuilder sb = new StringBuilder(text);
        sb.reverse();
        String rev = sb.toString();
        relay(sender, rev);
    }

    protected synchronized void handleMessage(ServerThread sender, String text) {
        relay(sender, text);
    }
    // end handle methods
}