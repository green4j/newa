package io.github.green4j.newa.rest;

import com.sun.management.ThreadMXBean;
import io.github.green4j.newa.text.LineAppendable;
import io.netty.handler.codec.http.FullHttpRequest;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.Map;

import static io.github.green4j.newa.rest.Util.inSleep;
import static io.github.green4j.newa.rest.Util.toDuration;

public class Txt_JvmThreadDump implements TxtRestHandle {

    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final LineAppendable output) {

        final ThreadMXBean threadMXBean =
                (ThreadMXBean) ManagementFactory.getThreadMXBean(); // unchecked
        final ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        final Map<Long, String> threadDescriptions = new HashMap<>();

        output.append("threads [").append(Integer.toString(threadInfos.length)).appendln(']');

        for (int i = 0; i < threadInfos.length; i++) {
            final ThreadInfo threadInfo = threadInfos[i];

            final Thread.State ts = threadInfo.getThreadState();
            final StackTraceElement[] stack = threadInfo.getStackTrace();

            final StringBuilder sb = new StringBuilder("[")
                    .append(threadInfo.getThreadName()).append("] #")
                    .append(threadInfo.getThreadId())
                    .append(threadInfo.isDaemon() ? " daemon" : "")
                    .append(" prio=").append(threadInfo.getPriority())
                    .append(" cpu=").append(
                            toDuration(
                                    threadMXBean.getThreadCpuTime(
                                            threadInfo.getThreadId()) / (1000 * 1000)))
                    .append(" user=").append(
                            toDuration(
                                    threadMXBean.getThreadUserTime(
                                            threadInfo.getThreadId()) / (1000 * 1000)))
                    .append(" mem=").append(
                            threadMXBean.getThreadAllocatedBytes(threadInfo.getThreadId()))
                    .append(' ').append(threadInfo.getThreadState());
            if (threadInfo.getLockName() != null) {
                sb.append(" on ").append(threadInfo.getLockName());
            }
            if (threadInfo.getLockOwnerName() != null) {
                sb.append(" owned by \"").append(threadInfo.getLockOwnerName())
                        .append("\" id=").append(threadInfo.getLockOwnerId());
            }
            if (threadInfo.isSuspended()) {
                sb.append(" (suspended)");
            }
            if (threadInfo.isInNative()) {
                sb.append(" (in native)");
            }

            if (ts == Thread.State.TIMED_WAITING
                    && stack != null && stack.length > 0) {
                final StackTraceElement top = stack[0];
                if (inSleep(top)) {
                    sb.append(" (sleeping)");
                }
            }

            output.tab(1).append(sb);

            threadDescriptions.put(threadInfo.getThreadId(), sb.toString());

            switch (ts) {
                case BLOCKED:
                    output.append(" blockedCount: ");
                    output.append(Long.toString(threadInfo.getBlockedCount()));
                    break;
                case WAITING:
                case TIMED_WAITING:
                    output.append(" waitedCount: ");
                    output.append(Long.toString(threadInfo.getWaitedCount()));
                    break;
                default:
                    break;
            }

            output.appendln();

            addStackTrace(output, threadInfo, sb);
        }

        final long[] dlt = threadMXBean.findDeadlockedThreads();
        output.append("deadlocks [").append(
                dlt != null ? Integer.toString(dlt.length) : "0"
        ).append("]\n");
        if (dlt != null) {
            for (final long tid : dlt) {
                final String td = threadDescriptions.get(tid);
                if (td == null) {
                    output.tab(1).append("#").appendln(Long.toString(tid));
                    continue;
                }
                output.tab(1).appendln(td);
            }
        }
    }

    private static void addStackTrace(final LineAppendable to,
                                      final ThreadInfo threadInfo,
                                      final StringBuilder sb) {
        final Thread.State ts = threadInfo.getThreadState();
        final LockInfo lock = threadInfo.getLockInfo();

        final StackTraceElement[] stackTrace = threadInfo.getStackTrace();

        to.tab(2).append("stack [").append(Integer.toString(stackTrace.length)).appendln(']');

        for (int i = 0; i < stackTrace.length; i++) {
            final StackTraceElement ste = stackTrace[i];
            sb.setLength(0);
            sb.append(ste.toString());

            if (i == 0) {
                switch (ts) {
                    case BLOCKED:
                        sb.append(" - blocked on ").append(lock);
                        break;
                    case WAITING:
                    case TIMED_WAITING:
                        if ("java.lang.Object".equals(ste.getClassName())
                                && "wait".equals(ste.getMethodName())) {
                            sb.append(" - waiting on ").append(lock);
                        } else {
                            if (inSleep(ste)) {
                                sb.append(" - sleeping");
                            } else {
                                sb.append(" - parking");
                                if (lock != null) {
                                    sb.append(" to wait on ").append(lock);
                                }
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
            for (final MonitorInfo mi : threadInfo.getLockedMonitors()) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append(" - locked ").append(mi);
                }
            }
            to.tab(3).appendln(sb);
        }

        final LockInfo[] locks = threadInfo.getLockedSynchronizers();
        if (locks.length > 0) {
            to.tab(2).append("lockedSynchronizers [").append(Integer.toString(locks.length)).appendln("]");
            for (final LockInfo li : locks) {
                to.tab(3).appendln(li.toString());
            }
        }
    }
}
