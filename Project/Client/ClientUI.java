package Project.Client;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import Project.Client.Interfaces.ICardControls;
import Project.Client.Interfaces.IConnectionEvents;
import Project.Client.Interfaces.IMessageEvents;
import Project.Client.Interfaces.IRoomEvents;
import Project.Client.Views.ChatGamePanel;
import Project.Client.Views.ConnectionPanel;
import Project.Client.Views.Menu;
import Project.Client.Views.RoomsPanel;
import Project.Client.Views.UserDetailsPanel;
import Project.Common.Constants;
import Project.Common.LoggerUtil;

/**
 * ClientUI is the main application window that manages different screens and
 * handles client events.
 */
public class ClientUI extends JFrame implements IConnectionEvents, IMessageEvents, IRoomEvents, ICardControls {
    private CardLayout card = new CardLayout(); // Layout manager to switch between different screens
    private Container container; // Container to hold different panels
    private JPanel cardContainer;
    private String originalTitle;
    private JPanel currentCardPanel;
    private CardView currentCard = CardView.CONNECT;
    private JMenuBar menu;
    private ConnectionPanel connectionPanel;
    private UserDetailsPanel userDetailsPanel;
    private ChatGamePanel chatGamePanel;
    private RoomsPanel roomsPanel;
    private JLabel roomLabel = new JLabel();

    {
        //you took it from here trinity
    }

    /**
     * Constructor to create the main application window.
     * 
     * @param title The title of the window.
     */
    public ClientUI(String title) {
        super(title); // Call the parent's constructor to set the frame title
        originalTitle = title;
        container = getContentPane();
        cardContainer = new JPanel();
        cardContainer.setLayout(card);
        container.add(roomLabel, BorderLayout.NORTH);
        container.add(cardContainer, BorderLayout.CENTER);
        cardContainer.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                cardContainer.setPreferredSize(e.getComponent().getSize());
                cardContainer.revalidate();
                cardContainer.repaint();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                // No specific action on move
            }
        });

        setMinimumSize(new Dimension(400, 400));
        setSize(getMinimumSize());
        setLocationRelativeTo(null); // Center the window
        menu = new Menu(this);
        this.setJMenuBar(menu);

        // Initialize panels
        connectionPanel = new ConnectionPanel(this);
        userDetailsPanel = new UserDetailsPanel(this);
        chatGamePanel = new ChatGamePanel(this);
        roomsPanel = new RoomsPanel(this);

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                int response = JOptionPane.showConfirmDialog(cardContainer,
                        "Are you sure you want to close this window?", "Close Window?",
                        JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (response == JOptionPane.YES_OPTION) {
                    try {
                        Client.INSTANCE.sendDisconnect();
                    } catch (NullPointerException | IOException e) {
                        LoggerUtil.INSTANCE.severe("Error during disconnect: " + e.getMessage());
                    }
                    System.exit(0);
                }
            }
        });

        pack(); // Resize to fit components
        setVisible(true); // Show the window
    }

    /**
     * Finds the current visible panel and updates the current card state.
     */
    private void findAndSetCurrentPanel() {
        for (Component c : cardContainer.getComponents()) {
            if (c.isVisible()) {
                currentCardPanel = (JPanel) c;
                currentCard = Enum.valueOf(CardView.class, currentCardPanel.getName());
                // Ensure connection for specific views
                if (Client.INSTANCE.getMyClientId() == Constants.DEFAULT_CLIENT_ID
                        && currentCard.ordinal() >= CardView.CHAT.ordinal()) {
                    show(CardView.CONNECT.name());
                    setSize(getMinimumSize());
                    revalidate();
                }
                break;
            }
        }
        LoggerUtil.INSTANCE.fine("Current panel: " + currentCardPanel.getName());
    }

    @Override
    public void next() {
        card.next(cardContainer);
        findAndSetCurrentPanel();
    }

    @Override
    public void previous() {
        card.previous(cardContainer);
        findAndSetCurrentPanel();
    }

    @Override
    public void show(String cardName) {
        card.show(cardContainer, cardName);
        findAndSetCurrentPanel();
    }

    @Override
    public void addPanel(String cardName, JPanel panel) {
        cardContainer.add(panel, cardName);
    }

    @Override
    public void connect() {
        String username = userDetailsPanel.getUsername();
        String host = connectionPanel.getHost();
        int port = connectionPanel.getPort();
        setTitle(originalTitle + " - " + username);
        Client.INSTANCE.connect(host, port, username, this);
    }

    public static void main(String[] args) {
        // TODO update with your UCID instead of mine
        // Your test or app entry point

        SwingUtilities.invokeLater(() -> {

            try {

                new ClientUI("tdc28-Client");

            } catch (Throwable t) {
                LoggerUtil.INSTANCE.severe("Unhandled exception in main thread", t);
            }
        });

    }

    // Interface methods start

    @Override
    public void onClientDisconnect(long clientId) {
        if (currentCard.ordinal() >= CardView.CHAT.ordinal()) {
            chatGamePanel.getChatPanel().removeUserListItem(clientId);
            boolean isMe = clientId == Client.INSTANCE.getMyClientId();
            String message = String.format("*%s disconnected*",
                    isMe ? "You" : Client.INSTANCE.getDisplayNameFromId(clientId));
            chatGamePanel.getChatPanel().addText(message);
            if (isMe) {
                LoggerUtil.INSTANCE.info("I disconnected");
                roomLabel.setText(""); // reset label
                previous();
            }
        }
    }

    @Override
    public void onMessageReceive(long clientId, String message) {
        if (currentCard.ordinal() >= CardView.CHAT.ordinal()) {

            if (clientId < Constants.DEFAULT_CLIENT_ID) {
                // Note: Planning to use < -1 as internal channels (see GameEventsPanel)
                return;
            }
            String displayName = Client.INSTANCE.getDisplayNameFromId(clientId);
            // added color to differentiate between room and user messages
            String name = clientId == Constants.DEFAULT_CLIENT_ID ? "<font color=blue>Room</font>"
                    : String.format("<font color=purple>%s</font>", displayName);

            chatGamePanel.getChatPanel().addText(String.format("%s: %s", name, message));
        }
    }

    @Override
    public void onReceiveClientId(long id) {
        LoggerUtil.INSTANCE.fine("Received client id: " + id);
        show(CardView.CHAT_GAME_SCREEN.name());
        chatGamePanel.getChatPanel().addText("*You connected*");
        setSize(new Dimension(600, 600));
        revalidate();
    }

    @Override
    public void onResetUserList() {
        chatGamePanel.getChatPanel().clearUserList();
    }

    @Override
    public void onReceiveRoomList(List<String> rooms, String message) {
        roomsPanel.removeAllRooms();
        if (message != null && !message.isEmpty()) {
            roomsPanel.setMessage(message);
        }
        if (rooms != null) {
            for (String room : rooms) {
                roomsPanel.addRoom(room);
            }
        }
    }

    @Override
    public void onRoomAction(long clientId, String roomName, boolean isJoin, boolean isQuiet) {
        LoggerUtil.INSTANCE.info("Current card: " + currentCard.name());
        if (currentCard.ordinal() >= CardView.CHAT.ordinal()) {
            // handle reset
            if (clientId == Constants.DEFAULT_CLIENT_ID) {
                chatGamePanel.getChatPanel().clearUserList();
                return;
            }
            String displayName = Client.INSTANCE.getDisplayNameFromId(clientId);
            if (isJoin) {
                roomLabel.setText("Room: " + roomName);
                chatGamePanel.getChatPanel().addUserListItem(clientId, displayName);
            } else {
                chatGamePanel.getChatPanel().removeUserListItem(clientId);
            }
            // generate message if not quiet sync
            if (!isQuiet) {
                boolean isMe = clientId == Client.INSTANCE.getMyClientId();
                String message = String.format("*%s %s the Room %s*",
                        /* 1st %s */ isMe ? "You" : displayName,
                        /* 2nd %s */ isJoin ? "joined" : "left",
                        /* 3rd %s */ roomName == null ? "" : roomName); // added handling of null after the demo video
                chatGamePanel.getChatPanel().addText(message);
            }
        }

    }

    // Interface methods end
}