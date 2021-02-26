package il.ac.bgu.se.bp.debugger;

import java.util.Map;

public interface BPJsDebuggerRunner<T> extends Debugger<T> {

    void setup(Map<Integer, Boolean> breakpoints, boolean isSkipSyncPoints);
    boolean isSetup();

    void start(Map<Integer, Boolean> breakpoints);
    boolean isStarted();

    T addExternalEvent(String externalEvent);
    T removeExternalEvent(String externalEvent);
    T setWaitForExternalEvents(boolean shouldWait);
    T startSync(boolean isSkipSyncPoints);
    T nextSync();
    T setIsSkipSyncPoints(boolean isSkipSyncPoints);
    T getSyncSnapshotsHistory();
    T setSyncSnapshots(long snapShotTime);
}
