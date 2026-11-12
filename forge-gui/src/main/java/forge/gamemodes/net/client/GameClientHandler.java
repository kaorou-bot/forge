package forge.gamemodes.net.client;

import forge.game.*;
import forge.game.player.PlayerView;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.GameProtocolHandler;
import forge.gamemodes.net.GuiRequestControl;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.GuiRequestScope;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.ReplyEvent;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.TrackableSerializer;
import forge.gamemodes.net.event.LoginEvent;
import forge.gui.interfaces.IGuiGame;
import forge.util.BuildInfo;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.Tracker;
import io.netty.channel.ChannelHandlerContext;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.FutureTask;

final class GameClientHandler extends GameProtocolHandler<IGuiGame> implements IHasForgeLog {

    private final FGameClient client;
    private final IGuiGame gui;
    private Tracker tracker;
    private volatile boolean clockProtocol;
    private final java.util.Map<Integer, GuiRequestScope> pendingChoices = new java.util.concurrent.ConcurrentHashMap<>();

    public GameClientHandler(final FGameClient client) {
        super(true);
        this.client = client;
        this.gui = client.getGui();
        this.tracker = null;
    }

    @Override
    protected ReplyPool getReplyPool(final ChannelHandlerContext ctx) {
        return client.getReplyPool();
    }

    @Override
    protected IRemote getRemote(final ChannelHandlerContext ctx) {
        return client;
    }

    @Override
    protected void sendReply(final ChannelHandlerContext ctx, final ReplyEvent event) {
        // Never re-route an old invocation through FGameClient's replacement channel.
        if (isCurrentConnection(ctx) && ctx.channel().isActive()) ctx.channel().writeAndFlush(event);
    }

    @Override
    protected IGuiGame getToInvoke(final ChannelHandlerContext ctx) {
        return gui;
    }

    @Override
    protected boolean isCurrentConnection(final ChannelHandlerContext ctx) {
        return client.isActiveChannel(ctx.channel());
    }

    @Override
    protected Runnable decorateInvocation(final ChannelHandlerContext ctx, final GuiGameEvent event,
                                          final Runnable invocation) {
        final ProtocolMethod method = event.getMethod();
        if (method.getReturnType().equals(Void.TYPE) && method != ProtocolMethod.message
                && method != ProtocolMethod.showErrorDialog) {
            return super.decorateInvocation(ctx, event, invocation);
        }
        final GuiRequestScope scope = new GuiRequestScope(new GuiRequestScope.Activity() {
            @Override public void ready() {
                if (clockProtocol) sendReply(ctx, GuiRequestControl.ready(event.getId()));
            }
        });
        pendingChoices.put(event.getId(), scope); // Include work not yet shown on the UI thread.
        return GuiRequestScope.bind(scope, () -> {
            try {
                if (!scope.isCancelled() && ctx.channel().isActive() && isCurrentConnection(ctx)) invocation.run();
            } finally {
                pendingChoices.remove(event.getId(), scope);
                scope.close();
            }
        });
    }

    private void cancelPendingChoices() {
        for (final GuiRequestScope scope : pendingChoices.values()) scope.close();
        pendingChoices.clear();
    }

    @Override
    protected void handleGuiRequestControl(final ChannelHandlerContext ctx, final Object value) {
        if (GuiRequestControl.HELLO.equals(value)) { clockProtocol = true; return; }
        final Integer barrier = GuiRequestControl.requestId(value, "barrier:");
        if (barrier != null && clockProtocol) {
            forge.gui.FThreads.invokeInEdtLater(() -> {
                if (isCurrentConnection(ctx)) sendReply(ctx, GuiRequestControl.ready(barrier));
            });
            return;
        }
        if (clockProtocol && value instanceof String text && text.startsWith("clock:")) {
            final int split = text.indexOf(':', 6);
            if (split < 0 || text.length() > 2080) return;
            try {
                final int gameId = Integer.parseInt(text.substring(6, split));
                final String display = text.substring(split + 1);
                forge.gui.FThreads.invokeInEdtLater(() -> {
                    if (isCurrentConnection(ctx)) gui.updateOperationClockForGame(gameId, display);
                });
            } catch (final NumberFormatException ignored) { }
            return;
        }
        final Integer id = GuiRequestControl.cancelledRequest(value);
        if (id == null) return;
        final GuiRequestScope scope = pendingChoices.get(id);
        if (scope != null) scope.close();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        cancelPendingChoices(); // This handler belongs to this channel, not its replacement.
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        cancelPendingChoices();
        super.handlerRemoved(ctx);
    }

    @Override
    protected Object[] decodeArgs(final ProtocolMethod protocolMethod, final byte[] encodedArgs) {
        try {
            // Deltas are applied on the GUI thread in arrival order, so a prompt handled on another thread waits for the ones before it
            final FutureTask<Void> deltasApplied = new FutureTask<>(() -> {}, null);
            FThreads.invokeInEdtNowOrLater(deltasApplied);
            deltasApplied.get();
            final Object[] args = TrackableSerializer.decodeArgs(encodedArgs, tracker);
            protocolMethod.checkArgs(args);
            updateTrackers(args);
            return args;
        } catch (final Exception e) {
            // Handled like a failed call, so the host still gets its reply
            netLog.error(e, "Failed to decode args for {}", protocolMethod);
            return null;
        }
    }

    @Override
    protected boolean shouldDispatchToGuiThread(final ProtocolMethod protocolMethod) {
        // Libgdx modal prompts block via WaitCallback and deadlock on the GL thread. Return-value
        // methods always block; message/showErrorDialog return void but still open a blocking modal.
        if (GuiBase.getInterface().isLibgdxPort()
                && (!protocolMethod.getReturnType().equals(Void.TYPE)
                    || protocolMethod == ProtocolMethod.message
                    || protocolMethod == ProtocolMethod.showErrorDialog)) {
            return false;
        }
        return super.shouldDispatchToGuiThread(protocolMethod);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void beforeCall(final ChannelHandlerContext ctx, final ProtocolMethod protocolMethod, final Object[] args) {
        switch (protocolMethod) {
            case setGameView:
                if (args.length > 0 && args[0] instanceof GameView gameView) {
                    // Each full view restarts delta sync on the host, so the client starts a new tracker as the host does per game
                    this.tracker = new Tracker();
                    // Encoder uses the tracker to emit IdRef for client→server CardView args
                    // (presence check only — stale detection is server-only).
                    // Ephemerals absent from the tracker serialize as full objects in both directions.
                    CompatibleObjectEncoder encoder = ctx.pipeline().get(CompatibleObjectEncoder.class);
                    if (encoder != null) {
                        encoder.setTracker(this.tracker);
                    }
                    CompatibleObjectDecoder decoder = ctx.pipeline().get(CompatibleObjectDecoder.class);
                    if (decoder != null) {
                        decoder.setTracker(this.tracker);
                    }
                    if (gameView.getGameLog() == null) {
                        gameView.initGameLog();
                    }
                    if (gameView.getTracker() == null) {
                        updateTrackers(new Object[]{gameView});
                    }
                }
                break;
            case openView:
                client.setInMatch(true);
                gui.setNetGame();
                final TrackableCollection<PlayerView> myPlayers = (TrackableCollection<PlayerView>) args[0];
                for (PlayerView myPlayer : myPlayers) {
                    if (myPlayer.getTracker() == null) {
                        myPlayer.setTracker(this.tracker);
                    }
                }
                client.setGameControllers(myPlayers);
                break;
            case finishGame:
                cancelPendingChoices();
                client.setInMatch(false);
                break;
            default:
                break;
        }
        if (this.tracker != null) {
            updateTrackers(args);
            // On the IO thread: the GUI's own lookup runs on the EDT, too late for the next message's IdRefs
            if (protocolMethod == ProtocolMethod.setGameView && args.length > 0 && args[0] instanceof GameView gv) {
                gv.updateObjLookup();
            }
        }
    }

    @Override
    protected void afterCall(final ChannelHandlerContext ctx, final ProtocolMethod method) {
        // Resume controls only after the authoritative snapshot has been applied on the UI thread.
        if (method == ProtocolMethod.setGameView && client.isActiveChannel(ctx.channel())) {
            client.onResumeResponseReceived();
        }
    }

    /**
     * This method is used to recursively update the <b>tracker</b>
     * references on all objects and their props.
     *
     * <p>Inline-serialized CardViews are intentionally NOT registered in the
     * tracker's id lookup: a tracker miss is the symmetric signal that a
     * CardView is ephemeral, mirroring the host's encoder check.
     *
     * @param objs
     */
    private void updateTrackers(final Object[] objs) {
        for (Object obj: objs) {
            if (obj instanceof TrackableObject trackableObject) {
                if (trackableObject.getTracker() == null) {
                    trackableObject.setTracker(this.tracker);
                    // walk the props
                    Map props = trackableObject.getProps();
                    if (props != null) {
                        for (Object propObj : props.values()) {
                            updateTrackers(new Object[]{propObj});
                        }
                    }
                }
            } else if (obj instanceof TrackableCollection collection) {
                Iterator itrCollection = collection.iterator();
                while (itrCollection.hasNext()) {
                    Object objCollection = itrCollection.next();
                    updateTrackers(new Object[]{objCollection});
                }
            }
        }
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        String loginName = client.getUsername();
        if (loginName == null || loginName.isEmpty()) {
            loginName = FModel.getPreferences().getPref(FPref.PLAYER_NAME);
        }
        ctx.channel().writeAndFlush(new LoginEvent(
                loginName,
                Integer.parseInt(FModel.getPreferences().getPref(FPref.UI_AVATARS).split(",")[0]),
                Integer.parseInt(FModel.getPreferences().getPref(FPref.UI_SLEEVES).split(",")[0]),
                GuiRequestControl.loginVersion(BuildInfo.getVersionString()),
                GuiBase.getInterface().isLibgdxPort()
        ));
    }

}
