package Project.Client.Interfaces;

public interface IStatusEvents {
    public void onAwayStatus(long clientId, boolean isAway);

    public void onReceiveAway(long clientId, boolean ready);
}