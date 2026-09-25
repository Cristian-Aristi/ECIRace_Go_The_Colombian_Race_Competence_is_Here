package edu.eci.arsw.eciracego.model;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Room {

    private String roomCode;
    private String hostName;
    private String status; // "WAITING", "IN_PROGRESS", "FINISHED"
    private long createdAt;
    private final Set<String> activeRunners = ConcurrentHashMap.newKeySet();

    public Room() {
        this.createdAt = System.currentTimeMillis();
        this.status = "WAITING";
    }

    public Room(String roomCode, String hostName) {
        this.roomCode = roomCode;
        this.hostName = hostName;
        this.status = "WAITING";
        this.createdAt = System.currentTimeMillis();
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Set<String> getActiveRunners() {
        return Collections.unmodifiableSet(activeRunners);
    }

    public int getRunnersCount() {
        return activeRunners.size();
    }

    public void addRunner(String runnerId) {
        if (runnerId != null && !runnerId.trim().isEmpty()) {
            activeRunners.add(runnerId.trim());
        }
    }

    public void removeRunner(String runnerId) {
        if (runnerId != null) {
            activeRunners.remove(runnerId.trim());
        }
    }
}