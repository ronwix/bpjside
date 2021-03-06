package il.ac.bgu.se.bp.utils;

import il.ac.bgu.cs.bp.bpjs.model.BEvent;
import il.ac.bgu.cs.bp.bpjs.model.BProgramSyncSnapshot;
import il.ac.bgu.cs.bp.bpjs.model.BThreadSyncSnapshot;
import il.ac.bgu.cs.bp.bpjs.model.SyncStatement;
import il.ac.bgu.cs.bp.bpjs.model.eventsets.EventSet;
import il.ac.bgu.se.bp.debugger.state.BPDebuggerState;
import il.ac.bgu.se.bp.debugger.state.BThreadInfo;
import il.ac.bgu.se.bp.debugger.state.EventInfo;
import il.ac.bgu.se.bp.debugger.state.EventsStatus;
import il.ac.bgu.se.bp.execution.RunnerState;
import org.mozilla.javascript.*;
import org.mozilla.javascript.tools.debugger.Dim;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static il.ac.bgu.cs.bp.bpjs.model.eventsets.EventSets.none;

public class DebuggerStateHelper {

    public static BPDebuggerState generateDebuggerState(BProgramSyncSnapshot syncSnapshot, RunnerState state, Dim.ContextData lastContextData) {
        List<BThreadInfo> bThreadInfoList = syncSnapshot
                .getBThreadSnapshots()
                .stream()
                .map(bThreadSyncSnapshot -> createBThreadInfo(bThreadSyncSnapshot, state, lastContextData))
                .collect(Collectors.toList());

        Set<SyncStatement> statements = syncSnapshot.getStatements();
        List<EventSet> wait = statements.stream().map(SyncStatement::getWaitFor).collect(Collectors.toList());
        List<EventSet> blocked = statements.stream().map(SyncStatement::getBlock).collect(Collectors.toList());
        List<BEvent> requested = statements.stream().map(SyncStatement::getRequest).flatMap(Collection::stream).collect(Collectors.toList());
        Set<EventInfo> waitEvents = wait.stream().map((e) -> new EventInfo(e.equals(none) ? "" : ((BEvent) e).getName())).collect(Collectors.toSet());
        Set<EventInfo> blockedEvents = blocked.stream().map((e) -> new EventInfo(e.equals(none) ? "" : ((BEvent) e).getName())).collect(Collectors.toSet());
        Set<EventInfo> requestedEvents = requested.stream().map((e) -> new EventInfo(e.getName())).collect(Collectors.toSet());

        EventsStatus eventsStatus = new EventsStatus(waitEvents, blockedEvents, requestedEvents);
        return new BPDebuggerState(bThreadInfoList, eventsStatus);
    }

    private static BThreadInfo createBThreadInfo(BThreadSyncSnapshot bThreadSS, RunnerState state, Dim.ContextData lastContextData) {
        ScriptableObject scope = (ScriptableObject) bThreadSS.getScope();
        try {
            Object implementation = getValue(scope, "implementation");
            Dim.StackFrame debuggerFrame = (Dim.StackFrame) getValue(implementation, "debuggerFrame");
            Map<Integer, Map<String, String>> env = state == null ? null :
                    state.getDebuggerState() == RunnerState.State.JS_DEBUG ? getEnvDebug(implementation, lastContextData) :
                            getEnv(debuggerFrame.contextData(), implementation);
            EventSet waitFor = bThreadSS.getSyncStatement().getWaitFor();
            EventInfo waitEvent = new EventInfo(waitFor.equals(none) ? "" : ((BEvent) waitFor).getName());
            EventSet blocked = bThreadSS.getSyncStatement().getBlock();
            EventInfo blockedEvent = new EventInfo(blocked.equals(none) ? "" : ((BEvent) blocked).getName());
            Set<EventInfo> requested = new ArrayList<>(bThreadSS.getSyncStatement().getRequest()).stream().map((r) -> new EventInfo(r.getName())).collect(Collectors.toSet());
            return new BThreadInfo(bThreadSS.getName(), env, waitEvent, blockedEvent, requested);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Object getValue(Object instance, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field fld = instance.getClass().getDeclaredField(fieldName);
        fld.setAccessible(true);
        return fld.get(instance);
    }

    private static Map<Integer, Map<String, String>> getEnvDebug(Object interpreterCallFrame, Dim.ContextData lastContextData) {
        Map<Integer, Map<String, String>> env = new HashMap<>();
        Integer key = 0;
        Context cx = Context.getCurrentContext();
        boolean currentBT = false;
        try {
            Object lastInterpreterFrame = getValue(cx, "lastInterpreterFrame");
            Object parentFrame = getValue(lastInterpreterFrame, "parentFrame");
            ScriptableObject interruptedScope = (ScriptableObject) getValue(parentFrame, "scope");
            ScriptableObject paramScope = (ScriptableObject) getValue(interpreterCallFrame, "scope");
            if (paramScope == interruptedScope) { //current running bthread
                currentBT = true;
                for (int i = 0; i < lastContextData.frameCount(); i++) {
                    ScriptableObject scope = (ScriptableObject) lastContextData.getFrame(i).scope();
                    env.put(i, getScope(scope));
                }
                key = lastContextData.frameCount();
            }
            parentFrame = interpreterCallFrame;
            while (parentFrame != null) {
                if (currentBT) {
                    Dim.ContextData debuggerFrame = ((Dim.StackFrame) getValue(parentFrame, "debuggerFrame")).contextData();
                    for (int i = 0; i < debuggerFrame.frameCount(); i++) {
                        ScriptableObject scope = (ScriptableObject) debuggerFrame.getFrame(i).scope();
                        env.put(key, getScope(scope));
                    }
                    key += debuggerFrame.frameCount();
                } else {
                    ScriptableObject scope = (ScriptableObject) getValue(parentFrame, "scope");
                    env.put(key, getScope(scope));
                    key += 1;
                }
                parentFrame = getValue(parentFrame, "parentFrame");
            }

        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        return env;
    }

    private static Map<Integer, Map<String, String>> getEnv(Dim.ContextData contextData, Object interpreterCallFrame) {
        Map<Integer, Map<String, String>> env = new HashMap<>();
        for (int i = 0; i < contextData.frameCount(); i++) {
            ScriptableObject scope = (ScriptableObject) contextData.getFrame(i).scope();
            env.put(i, getScope(scope));
        }
        Integer key = 1;
        try {
            Object lastFrame = interpreterCallFrame;
            ScriptableObject scope = (ScriptableObject) getValue(lastFrame, "scope");
            env.put(0, getScope(scope));
            Object parentFrame = getValue(lastFrame, "parentFrame");
            while (parentFrame != null) {
                Dim.ContextData debuggerFrame = ((Dim.StackFrame) getValue(parentFrame, "debuggerFrame")).contextData();
                scope = (ScriptableObject) getValue(parentFrame, "scope");
                env.put(key, getScope(scope));
                key += 1;
                parentFrame = getValue(parentFrame, "parentFrame");
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return env;
    }

    private static Map<String, String> getScope(ScriptableObject scope) {
        Map<String, String> myEnv = new HashMap<>();
        try {
            Object function = getValue(scope, "function");
            Object interpeterData = getValue(function, "idata");
            String itsName = (String) getValue(interpeterData, "itsName");
            myEnv.put("FUNCNAME", itsName != null ? itsName : "BTMain");
            Object[] ids = Arrays.stream(scope.getIds()).filter((p) -> !p.toString().equals("arguments") && !p.toString().equals(itsName + "param")).toArray();
            for (Object id : ids) {
                myEnv.put(id.toString(), Objects.toString(collectJsValue(scope.get(id))));
            }
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return myEnv;
    }

    /**
     * Take a Javascript value from Rhino, build a Java value for it.
     *
     * @param jsValue
     * @return
     */
    private static Object collectJsValue(Object jsValue) {
        if (jsValue == null) {
            return null;

        } else if (jsValue instanceof NativeFunction) {
            return ((NativeFunction) jsValue).getEncodedSource();

        } else if (jsValue instanceof NativeArray) {
            NativeArray jsArr = (NativeArray) jsValue;
            List<Object> retVal = new ArrayList<>((int) jsArr.getLength());
            for (int idx = 0; idx < jsArr.getLength(); idx++) {
                retVal.add(collectJsValue(jsArr.get(idx)));
            }
            return retVal;

        } else if (jsValue instanceof ScriptableObject) {
            ScriptableObject jsObj = (ScriptableObject) jsValue;
            Map<Object, Object> retVal = new HashMap<>();
            for (Object key : jsObj.getIds()) {
                retVal.put(key, collectJsValue(jsObj.get(key)));
            }
            return retVal;

        } else if (jsValue instanceof ConsString) {
            return ((ConsString) jsValue).toString();

        } else if (jsValue instanceof NativeJavaObject) {
            NativeJavaObject jsJavaObj = (NativeJavaObject) jsValue;
            Object obj = jsJavaObj.unwrap();
            return obj;

        } else {
            return jsValue;
        }

    }

}