package il.ac.bgu.se.bp.service;

import il.ac.bgu.cs.bp.bpjs.execution.BProgramRunner;
import il.ac.bgu.se.bp.debugger.BPJsDebugger;
import il.ac.bgu.se.bp.error.ErrorCode;
import il.ac.bgu.se.bp.execution.BPJsDebuggerImpl;
import il.ac.bgu.se.bp.logger.Logger;
import il.ac.bgu.se.bp.rest.request.DebugRequest;
import il.ac.bgu.se.bp.rest.request.RunRequest;
import il.ac.bgu.se.bp.rest.request.SetBreakpointRequest;
import il.ac.bgu.se.bp.rest.request.ToggleBreakpointsRequest;
import il.ac.bgu.se.bp.rest.response.BooleanResponse;
import il.ac.bgu.se.bp.service.code.SourceCodeHelper;
import il.ac.bgu.se.bp.service.manage.SessionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import org.thymeleaf.util.StringUtils;


import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
@EnableScheduling
public class BPjsIDEServiceImpl implements BPjsIDEService {

//    @Scheduled(fixedRate = 1000)
//    public void threadsCount() {
//        logger.info("THREADS COUNT: {0}", Thread.activeCount());
//    }

    private static final Logger logger = new Logger(BPjsIDEServiceImpl.class);

    @Autowired
    private SessionHandler<BProgramRunner> sessionHandler;

    @Autowired
    private SourceCodeHelper sourceCodeHelper;

    @Override
    public BooleanResponse subscribeUser(String sessionId, String userId) {
        System.out.println("Received message from {1} with sessionId {2}" + ",," + userId + "," + sessionId);
        sessionHandler.addUser(sessionId, userId);
        return new BooleanResponse(true);
    }

    @Override
    public BooleanResponse run(RunRequest runRequest, String userId) {
        BProgramRunner bProgramRunner = new BProgramRunner();

        sessionHandler.addNewRunExecution(userId, bProgramRunner);
        sessionHandler.updateLastOperationTime(userId);


        logger.info("received run request with code: {0}", bProgramRunner.toString());
        return new BooleanResponse(true);
    }

    @Override
    public BooleanResponse debug(DebugRequest debugRequest, String userId) {
        if (!validateRequest(debugRequest)) {
            return createErrorResponse(ErrorCode.INVALID_REQUEST);
        }

        if (!sessionHandler.validateUserId(userId)) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }

        String filepath = sourceCodeHelper.createCodeFile(debugRequest.getSourceCode());
        if (StringUtils.isEmpty(filepath)) {
            return createErrorResponse(ErrorCode.INVALID_SOURCE_CODE);
        }

        logger.info("received debug request for user: {0}", userId);
        return handleNewDebugRequest(debugRequest, userId, filepath);
    }

    private BooleanResponse handleNewDebugRequest(DebugRequest debugRequest, String userId, String filepath) {
        BPJsDebuggerImpl bpProgramDebugger = new BPJsDebuggerImpl(userId, filepath);
        bpProgramDebugger.subscribe(sessionHandler);

        sessionHandler.addNewDebugExecution(userId, bpProgramDebugger);
        sessionHandler.updateLastOperationTime(userId);

        Map<Integer, Boolean> breakpointsMap = debugRequest.getBreakpoints()
                .stream()
                .collect(Collectors.toMap(Function.identity(), b -> Boolean.TRUE));

        return bpProgramDebugger.startSync(breakpointsMap, debugRequest.isSkipBreakpointsToggle(), debugRequest.isSkipSyncStateToggle());
    }

    @Override
    public BooleanResponse setBreakpoint(String userId, SetBreakpointRequest setBreakpointRequest) {
        if (setBreakpointRequest == null) {
            return createErrorResponse(ErrorCode.INVALID_REQUEST);
        }

        BPJsDebugger<BooleanResponse> bpJsDebugger = sessionHandler.getBPjsDebuggerByUser(userId);
        if (bpJsDebugger == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }

        return bpJsDebugger.setBreakpoint(setBreakpointRequest.getLineNumber(), setBreakpointRequest.isStopOnBreakpoint());
    }

    @Override
    public BooleanResponse toggleMuteBreakpoints(String userId, ToggleBreakpointsRequest toggleBreakPointStatus) {
        if (toggleBreakPointStatus == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }

        BPJsDebugger<BooleanResponse> bpJsDebugger = sessionHandler.getBPjsDebuggerByUser(userId);
        if (bpJsDebugger == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }

        return bpJsDebugger.toggleMuteBreakpoints(toggleBreakPointStatus.isSkipBreakpoints());
    }

    @Override
    public BooleanResponse stop(String userId) {
        BPJsDebugger<BooleanResponse> bpJsDebugger = sessionHandler.getBPjsDebuggerByUser(userId);
        if (bpJsDebugger == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }
        return bpJsDebugger.stop();
    }

    @Override
    public BooleanResponse stepOut(String userId) {
        BPJsDebugger<BooleanResponse> bpJsDebugger = sessionHandler.getBPjsDebuggerByUser(userId);
        if (bpJsDebugger == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }
        return bpJsDebugger.stepOut();
    }

    @Override
    public BooleanResponse stepInto(String userId) {
        BPJsDebugger<BooleanResponse> bpJsDebugger = sessionHandler.getBPjsDebuggerByUser(userId);
        if (bpJsDebugger == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }
        return bpJsDebugger.stepInto();
    }

    @Override
    public BooleanResponse stepOver(String userId) {
        BPJsDebugger<BooleanResponse> bpJsDebugger = sessionHandler.getBPjsDebuggerByUser(userId);
        if (bpJsDebugger == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }
        return bpJsDebugger.stepOver();
    }

    @Override
    public BooleanResponse continueRun(String userId) {
        BPJsDebugger<BooleanResponse> bpJsDebugger = sessionHandler.getBPjsDebuggerByUser(userId);
        if (bpJsDebugger == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }

        return bpJsDebugger.continueRun();
    }

    @Override
    public BooleanResponse nextSync(String userId) {
        BPJsDebugger<BooleanResponse> bpJsDebugger = sessionHandler.getBPjsDebuggerByUser(userId);
        if (bpJsDebugger == null) {
            return createErrorResponse(ErrorCode.UNKNOWN_USER);
        }

        return bpJsDebugger.nextSync();
    }

    private BooleanResponse createErrorResponse(ErrorCode errorCode) {
        return new BooleanResponse(false, errorCode);
    }

    private boolean validateRequest(RunRequest runRequest) {
        return !StringUtils.isEmpty(runRequest.getSourceCode());
    }

}
