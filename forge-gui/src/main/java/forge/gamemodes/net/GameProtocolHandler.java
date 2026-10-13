package forge.gamemodes.net;

import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.ReplyEvent;
import forge.gui.FThreads;
import forge.gui.GuiRequestScope;
import forge.gui.util.SOptionPane;
import forge.localinstance.skin.FSkinProp;
import forge.trackable.TrackableObject;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public abstract class GameProtocolHandler<T> extends ChannelInboundHandlerAdapter implements IHasForgeLog {

    private final boolean runInEdt;
    protected GameProtocolHandler(final boolean runInEdt) {
        this.runInEdt = runInEdt;
    }

    protected abstract ReplyPool getReplyPool(ChannelHandlerContext ctx);
    protected abstract IRemote getRemote(ChannelHandlerContext ctx);

    protected abstract T getToInvoke(ChannelHandlerContext ctx);
    protected abstract void beforeCall(ChannelHandlerContext ctx, ProtocolMethod protocolMethod, Object[] args);

    protected void afterCall(ChannelHandlerContext ctx, ProtocolMethod protocolMethod) { }

    protected boolean isCurrentConnection(ChannelHandlerContext ctx) { return true; }

    protected void handleGuiRequestControl(final ChannelHandlerContext ctx, final Object value) { }
    protected boolean handleReply(final ChannelHandlerContext ctx, final ReplyEvent event) { return false; }

    protected void sendReply(final ChannelHandlerContext ctx, final ReplyEvent event) {
        final IRemote remote = getRemote(ctx);
        if (remote != null) remote.send(event);
    }

    protected Runnable decorateInvocation(final ChannelHandlerContext ctx, final GuiGameEvent event,
                                          final Runnable invocation) {
        return GuiRequestScope.bind(null, invocation);
    }

    protected boolean shouldDispatchToGuiThread(final ProtocolMethod protocolMethod) {
        return runInEdt;
    }

    @Override
    public final void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (!isCurrentConnection(ctx)) return;
        final String[] catchedError = {""};
        // NetworkGuiGame.applyDelta logs each delta itself
        if (!(msg instanceof GuiGameEvent delta && delta.getMethod() == ProtocolMethod.applyDelta)) {
            netLog.info("Received: {}", msg);
        }
        if (msg instanceof ReplyEvent event) {
            if (event.getIndex() == GuiRequestControl.INDEX) {
                handleGuiRequestControl(ctx, event.getReply());
                return;
            }
            final ReplyPool replies = getReplyPool(ctx);
            if (handleReply(ctx, event)) return;
            if (replies != null) replies.complete(event.getIndex(), event.getReply());
        } else if (msg instanceof GuiGameEvent event) {
            final ProtocolMethod protocolMethod = event.getMethod();
            final String methodName = protocolMethod.name();

            final Method method = protocolMethod.getMethod();
            if (method == null) {
                //throw new IllegalStateException(String.format("Method %s not found", protocolMethod.name()));
                catchedError[0] += String.format("IllegalStateException: Method %s not found (GameProtocolHandler.java Line 43)\n", protocolMethod.name());
                netLog.error("Method {} not found", protocolMethod.name());
            }

            final Object[] args = event.getObjects();
            protocolMethod.checkArgs(args);

            final Object toInvoke = getToInvoke(ctx);
            if (toInvoke == null) {
                netLog.info("Ignoring {} — controller no longer available (game ended)", methodName);
                // For methods expecting a reply, send null so the client doesn't hang
                final Class<?> earlyReturnType = protocolMethod.getReturnType();
                if (!earlyReturnType.equals(Void.TYPE)) {
                    sendReply(ctx, new ReplyEvent(event.getId(), null));
                }
                return;
            }

            // Pre-call actions (runs on IO thread — blocks all subsequent messages)
            final long beforeCallStart = System.currentTimeMillis();
            beforeCall(ctx, protocolMethod, args);
            final long beforeCallMs = System.currentTimeMillis() - beforeCallStart;
            if (beforeCallMs > 50) {
                netLog.info("beforeCall({}) took {} ms on IO thread", methodName, beforeCallMs);
            }

            final Class<?> returnType = protocolMethod.getReturnType();
            final long receiveTimeMs = System.currentTimeMillis();
            final Runnable toRun = () -> {
                if (!isCurrentConnection(ctx) || GuiRequestScope.isCurrentCancelled()) return;
                final long startMs = System.currentTimeMillis();
                final long queueDelayMs = startMs - receiveTimeMs;
                if (returnType.equals(Void.TYPE)) {
                    try {
                        method.invoke(toInvoke, args);
                        afterCall(ctx, protocolMethod);
                    } catch (final IllegalAccessException | IllegalArgumentException e) {
                        netLog.error("Unknown protocol method {} args={}", methodName, describeArgs(args));
                    } catch (final InvocationTargetException e) {
                        if (GuiRequestScope.isCurrentCancelled()) return;
                        //throw new RuntimeException(e.getTargetException());
                        catchedError[0] += (String.format("RuntimeException: %s (GameProtocolHandler.java Line 65)\n", e.getTargetException().toString()));
                        netLog.error(e.getTargetException(), "InvocationTargetException in {} args={}", methodName, describeArgs(args));
                    }
                } else {
                    Serializable reply = null;
                    try {
                        final Object theReply = method.invoke(toInvoke, args);
                        if (theReply instanceof Serializable) {
                            protocolMethod.checkReturnValue(theReply);
                            reply = (Serializable) theReply;
                        } else if (theReply != null) {
                            netLog.warn("Non-serializable return type {} for method {}, returning null", returnType.getName(), methodName);
                        }
                    } catch (final IllegalAccessException | IllegalArgumentException e) {
                        netLog.error("Unknown protocol method {} args={}, replying with null", methodName, describeArgs(args));
                    } catch (final NullPointerException | InvocationTargetException e) {
                        if (GuiRequestScope.isCurrentCancelled()) return;
                        //throw new RuntimeException(e.getTargetException());
                        catchedError[0] += e.toString();
                        SOptionPane.showMessageDialog(catchedError[0],
                                Localizer.getInstance().getMessage("lblError"), FSkinProp.ICO_WARNING);
                        netLog.error("Exception in protocol method {} args={}: {}", methodName, describeArgs(args), e.toString());
                    }
                    if (isCurrentConnection(ctx) && !GuiRequestScope.isCurrentCancelled()) {
                        sendReply(ctx, new ReplyEvent(event.getId(), reply));
                    }
                }
                final long elapsed = System.currentTimeMillis() - startMs;
                if (queueDelayMs > 50 || elapsed > 50) {
                    netLog.info("Protocol {} processed in {} ms (queued {} ms)", methodName, elapsed, queueDelayMs);
                }
            };

            final Runnable invocation = decorateInvocation(ctx, event, toRun);
            if (shouldDispatchToGuiThread(protocolMethod)) {
                FThreads.invokeInEdtNowOrLater(invocation);
            } else {
                FThreads.invokeInBackgroundThread(invocation);
            }
        }
    }

    private static String describeArgs(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            if (a == null) {
                sb.append("null");
            } else {
                sb.append(a.getClass().getSimpleName());
                if (a instanceof TrackableObject to) {
                    sb.append('#').append(to.getId());
                }
            }
        }
        return sb.append(']').toString();
    }

    @Override
    public final void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        netLog.info("Connection exception: {}", cause.getClass().getName());
        netLog.info("Message: {}", cause.getMessage());
        if (cause.getCause() != null) {
            netLog.info("Cause: {} - {}",
                cause.getCause().getClass().getName(), cause.getCause().getMessage());
        }
        // Log stack trace elements
        StackTraceElement[] stack = cause.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 10); i++) {
            netLog.info("  at {}", stack[i].toString());
        }
        ctx.close();
    }

}
