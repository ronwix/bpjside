package il.ac.bgu.se.bp.engine;

import il.ac.bgu.se.bp.debugger.DebuggerCommand;
import il.ac.bgu.se.bp.debugger.DebuggerEngine;
import il.ac.bgu.se.bp.execution.RunnerState;
import il.ac.bgu.se.bp.logger.Logger;
import org.mozilla.javascript.*;
import org.mozilla.javascript.tools.debugger.Dim;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;

public class DebuggerEngineImpl implements DebuggerEngine<FutureTask<String>, String> {
    private final Logger logger = new Logger(DebuggerEngineImpl.class);
    private Dim dim;
    private final BlockingQueue<FutureTask<String>> queue;
    private final String filename;
    private Dim.ContextData lastContextData = null;
    private volatile boolean isRunning;
    private RunnerState state;
    private volatile boolean areBreakpointsMuted = false;

    public DebuggerEngineImpl(String filename, RunnerState state) {
        this.filename = filename;
        this.state = state;
        queue = new ArrayBlockingQueue<>(1);
        dim = new Dim();
        dim.setGuiCallback(this);
        dim.attachTo(ContextFactory.getGlobal());
        setIsRunning(true);
    }

    public void setupBreakpoint(Map<Integer, Boolean> breakpoints) {
        breakpoints.forEach(this::setBreakpoint);
    }

    @Override
    public void updateSourceText(Dim.SourceInfo sourceInfo) {}

    @Override
    public void enterInterrupt(Dim.StackFrame stackFrame, String s, String s1) {
        this.state.setDebuggerState(RunnerState.State.JS_DEBUG);
        if (this.areBreakpointsMuted) {
            continueRun();
            return;
        }

        System.out.println("Breakpoint reached- " + s + " Line no: " + stackFrame.getLineNumber());
        this.lastContextData = stackFrame.contextData();
        Map<Integer, Map<String, String>> env = getEnv();
        for(Map.Entry e : env.entrySet()){
            System.out.println(e.getKey()+ ":" + e.getValue());
        }
        // Update service -> we on breakpoint! (apply callback)
    }

    @Override
    public boolean isGuiEventThread() {
        return true;
    }

    @Override
    public void dispatchNextGuiEvent() throws InterruptedException {
        queue.take().run();
    }

    public FutureTask<String> addCommand(DebuggerCommand<FutureTask<String>, String> command) {
        FutureTask<String> futureTask;
        if(this.state.getDebuggerState() == RunnerState.State.JS_DEBUG){
            futureTask = debuggerCommandToCallback(command);
            queue.add(futureTask);
            return futureTask;
        }
        futureTask = new FutureTask<>(() -> "Must be in js debug in order to execute this command");
        futureTask.run();
        return futureTask;
    }

    public FutureTask<String> debuggerCommandToCallback(DebuggerCommand<FutureTask<String>, String> command) {
        if (!isRunning()) {
            FutureTask<String> futureTask = new FutureTask<>(() -> "not running");
            futureTask.run();
            return futureTask;
        }

        return command.applyCommand(this);
    }

    public void run() {
        setIsRunning(true);
    }

    private synchronized boolean isRunning() {
        return isRunning;
    }

    private synchronized void setIsRunning(boolean isRunning) {
        this.isRunning = isRunning;
    }

    private synchronized void setAreBreakpointsMuted(boolean areBreakpointsMuted) {
        this.areBreakpointsMuted = areBreakpointsMuted;
    }

    public String stop() {
        dim = null;
        setIsRunning(false);
        return "stopped";
    }

    public String toggleMuteBreakpoints() {
        setAreBreakpointsMuted(!this.areBreakpointsMuted);
        return "breakpoints muted toggled to " + this.areBreakpointsMuted;
    }

    public String stepOut() {
        dim.setReturnValue(Dim.STEP_OUT);
        return "step into";
        //        return getDebuggerStatus();
    }

    public String stepInto() {
        dim.setReturnValue(Dim.STEP_INTO);
        return "step into";
        //        return getDebuggerStatus();
    }

    public String stepOver() {
        dim.setReturnValue(Dim.STEP_OVER);
        return "step over";
        //        return getDebuggerStatus();
    }

    public String exit() {
        dim.setReturnValue(Dim.EXIT);
        return "exit";
        //        return getDebuggerStatus();
    }

    public String continueRun() {
        this.dim.go();
        return "continue run";
//        return getDebuggerStatus();
    }

    public String setBreakpoint(int lineNumber, boolean stopOnBreakpoint) {
        try {
            Dim.SourceInfo sourceInfo = dim.sourceInfo(this.filename);
            sourceInfo.breakpoint(lineNumber, stopOnBreakpoint);
            return "after set breakpoint -" + " line " + lineNumber + " changed to " + stopOnBreakpoint;
//        return getDebuggerStatus();
        } catch (Exception e) {
            logger.error("cannot assign breakpoint on line {0}", lineNumber);
            return null;
        }
    }

    public String getVars() {
        StringBuilder vars = new StringBuilder();
        Dim.ContextData currentContextData = dim.currentContextData();
        for (int i = 0; i < currentContextData.frameCount(); i++) {
            vars.append("Scope no: ").append(i).append("\n");
            Dim.StackFrame stackFrame = currentContextData.getFrame(i);
            NativeCall scope = (NativeCall) stackFrame.scope();

            Object[] objects = ((Scriptable) scope).getIds();
            List<String> arguments = Arrays.stream(objects).map(Object::toString).collect(Collectors.toList()).subList(1, objects.length);
            for (String arg : arguments) {
                Object res = ScriptableObject.getProperty(scope, arg);
                if (Undefined.instance != res)
                    vars.append(arg).append(" ").append(res).append("\n");
            }
        }
        return "Vars: \n" + vars;
    }

    private Object getValue(Object instance, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field fld = instance.getClass().getDeclaredField(fieldName);
        fld.setAccessible(true);
        return fld.get(instance);
    }

    private Map<String, String> getScope(ScriptableObject scope){
        Map<String, String> myEnv = new HashMap<>();
        Object[] ids = Arrays.stream(scope.getIds()).skip(1).toArray();
        for(Object id: ids){
            myEnv.put(id.toString(), Objects.toString(scope.get(id)));
        }
        return myEnv;
    }

    private Map<Integer, Map<String, String>> getEnv() {
        Map<Integer, Map<String, String>> env = new HashMap<>();
        for (int i = 0; i < this.lastContextData.frameCount(); i++) {
            ScriptableObject scope = (ScriptableObject) this.lastContextData.getFrame(i).scope();
            env.put(i, getScope(scope));
        }
        Integer key = this.lastContextData.frameCount();
        // get from continuation frames
        Context cx = Context.getCurrentContext();
        try {
            Object lastFrame = getValue(cx, "lastInterpreterFrame");
            Object parentFrame = getValue(lastFrame, "parentFrame");
            while(parentFrame != null) {
                Dim.ContextData debuggerFrame = ((Dim.StackFrame) getValue(parentFrame, "debuggerFrame")).contextData();
                if (debuggerFrame != this.lastContextData) {
                    for (int i = 0; i < debuggerFrame.frameCount(); i++) {
                        ScriptableObject scope = (ScriptableObject) debuggerFrame.getFrame(i).scope();
                        env.put(key, getScope(scope));
                    }
                    key += debuggerFrame.frameCount();
                    parentFrame = getValue(parentFrame, "parentFrame");
                }
                else {
                    parentFrame = null;
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return env;
    }
}