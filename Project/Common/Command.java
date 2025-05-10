package Project.Common;

import java.io.IOException;
import java.util.HashMap;

public enum Command {
    QUIT("quit"),
    DISCONNECT("disconnect"),
    LOGOUT("logout"),
    LOGOFF("logoff"),
    REVERSE("reverse"),
    CREATE_ROOM("createroom"),
    LEAVE_ROOM("leaveroom"),
    JOIN_ROOM("joinroom"),
    NAME("name"),
    LIST_USERS("users"),
    LIST_ROOMS("listrooms"),
    READY("ready"),
    DO_SOMETHING("something"),
    USE("use"),
    PICK ("pick"),
    AWAY("away");
    

    private static final HashMap<String, Command> BY_COMMAND = new HashMap<>();
    static {
        for (Command e : values()) {
            BY_COMMAND.put(e.command, e);
        }
    }
    public final String command;

    private Command(String command) {
        this.command = command;
    }

    public static Command stringToCommand(String command) {
        return BY_COMMAND.get(command);
    } 
    public void handleCommand(String text) throws IOException {
        if (text.equalsIgnoreCase(Command.AWAY.command)) {
            sendAway();
        }
    }

    public void sendAway() throws IOException {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.AWAY);
            sendToServer(payload);
        }
    
        private void sendToServer(Payload payload) throws IOException {
            // Implement the logic to send the payload to the server
            System.out.println("Sending payload to server: " + payload);
    }
}