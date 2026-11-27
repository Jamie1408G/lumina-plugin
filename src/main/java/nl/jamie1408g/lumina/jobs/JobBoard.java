package nl.jamie1408g.lumina.jobs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job requests: a player asks for a role ("I need a LUPA Builder"), someone with that role accepts,
 * and the requester confirms when it's done.
 *
 * Travel while on an accepted job: faregates open for free, but the fares are noted. They're only
 * truly free once the requester confirms the job is done; if the worker drops it or runs out of
 * time, the noted fares are charged after all.
 */
public final class JobBoard {

    public enum State { OPEN, ACCEPTED }

    public static final class Job {
        public final int id;
        public final UUID requester;
        public final String requesterName;
        public final String roleKey;
        public final String note;
        public final long created;
        UUID worker;
        String workerName;
        long accepted;
        long owed;
        State state = State.OPEN;

        Job(int id, UUID requester, String requesterName, String roleKey, String note, long created) {
            this.id = id;
            this.requester = requester;
            this.requesterName = requesterName;
            this.roleKey = roleKey;
            this.note = note;
            this.created = created;
        }

        public UUID worker() { return worker; }
        public String workerName() { return workerName; }
        public long accepted() { return accepted; }
        /** Fares noted while on this job (free if it's completed). */
        public long owed() { return owed; }
        public State state() { return state; }
    }

    /** Most open requests one player can have at a time. */
    public static final int MAX_REQUESTS = 2;

    private final Map<Integer, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Long> freeRideHomeUntil = new HashMap<>();
    private int nextId = 1;

    /** @return the new job, or null if the player already has MAX_REQUESTS requests */
    public Job request(UUID requester, String name, String roleKey, String note, long now) {
        if (byRequester(requester).size() >= MAX_REQUESTS) return null;
        Job j = new Job(nextId++, requester, name, roleKey, note == null ? "" : note.trim(), now);
        jobs.put(j.id, j);
        return j;
    }

    public Job byId(int id) { return jobs.get(id); }

    public List<Job> open(String roleKey) {
        List<Job> out = new ArrayList<>();
        for (Job j : jobs.values()) if (j.state == State.OPEN && j.roleKey.equals(roleKey)) out.add(j);
        return out;
    }

    public List<Job> byRequester(UUID id) {
        List<Job> out = new ArrayList<>();
        for (Job j : jobs.values()) if (j.requester.equals(id)) out.add(j);
        return out;
    }

    /** The job this worker is on, or null. */
    public Job activeFor(UUID worker) {
        for (Job j : jobs.values()) if (j.state == State.ACCEPTED && worker.equals(j.worker)) return j;
        return null;
    }

    /** @return null when accepted, otherwise why not */
    public String accept(int id, UUID worker, String workerName, String workerRoleKey, long now) {
        Job j = jobs.get(id);
        if (j == null) return "There's no request #" + id + ".";
        if (j.state != State.OPEN) return "Request #" + id + " has already been taken.";
        if (!j.roleKey.equals(workerRoleKey)) return "Request #" + id + " is for a different role.";
        if (j.requester.equals(worker)) return "You can't take your own request.";
        if (activeFor(worker) != null) return "Finish or drop your current job first.";
        j.state = State.ACCEPTED;
        j.worker = worker;
        j.workerName = workerName;
        j.accepted = now;
        j.owed = 0;
        return null;
    }

    /** A fare the worker would have paid while on their job. Returns false if they're not on a job. */
    public boolean note(UUID worker, long fare) {
        Job j = activeFor(worker);
        if (j == null) return false;
        j.owed += fare;
        return true;
    }

    /**
     * The requester confirms their accepted job is done. The worker's noted fares are forgiven and
     * their next tap out within rideHomeMs is free too. Returns the finished job, or null.
     */
    public Job complete(UUID requester, Integer id, long now, long rideHomeMs) {
        Job j = pickAccepted(requester, id);
        if (j == null) return null;
        jobs.remove(j.id);
        freeRideHomeUntil.put(j.worker, now + rideHomeMs);
        return j;
    }

    /** The worker gives up. The job goes back to OPEN; the caller charges job.owed() (read it before). */
    public Job drop(UUID worker) {
        Job j = activeFor(worker);
        if (j == null) return null;
        Job copy = snapshot(j);
        j.state = State.OPEN;
        j.worker = null;
        j.workerName = null;
        j.owed = 0;
        return copy;
    }

    /** The requester withdraws a request. The worker (if any) isn't charged: it wasn't their fault. */
    public Job cancel(UUID requester, Integer id) {
        Job j = null;
        if (id != null) {
            Job k = jobs.get(id);
            if (k != null && k.requester.equals(requester)) j = k;
        } else {
            List<Job> mine = byRequester(requester);
            if (mine.size() == 1) j = mine.get(0);
        }
        if (j == null) return null;
        jobs.remove(j.id);
        if (j.worker != null) freeRideHomeUntil.put(j.worker, Long.MAX_VALUE / 2); // not their fault: ride home free
        return j;
    }

    /** Accepted jobs past their time limit (caller charges the worker) and open requests nobody took. */
    public List<Job> expire(long now, long acceptedLimitMs, long openLimitMs) {
        List<Job> gone = new ArrayList<>();
        for (Job j : new ArrayList<>(jobs.values())) {
            boolean late = j.state == State.ACCEPTED ? now - j.accepted > acceptedLimitMs : now - j.created > openLimitMs;
            if (late) {
                gone.add(j);
                jobs.remove(j.id);
            }
        }
        return gone;
    }

    /** After a completed (or cancelled) job, the worker's next tap out is free. Uses it up. */
    public boolean useRideHome(UUID worker, long now) {
        Long until = freeRideHomeUntil.get(worker);
        if (until == null) return false;
        freeRideHomeUntil.remove(worker);
        return now <= until;
    }

    private Job pickAccepted(UUID requester, Integer id) {
        if (id != null) {
            Job j = jobs.get(id);
            return j != null && j.requester.equals(requester) && j.state == State.ACCEPTED ? j : null;
        }
        Job found = null;
        for (Job j : jobs.values()) {
            if (j.requester.equals(requester) && j.state == State.ACCEPTED) {
                if (found != null) return null; // more than one: they have to say which
                found = j;
            }
        }
        return found;
    }

    private static Job snapshot(Job j) {
        Job c = new Job(j.id, j.requester, j.requesterName, j.roleKey, j.note, j.created);
        c.worker = j.worker;
        c.workerName = j.workerName;
        c.accepted = j.accepted;
        c.owed = j.owed;
        c.state = j.state;
        return c;
    }
}
