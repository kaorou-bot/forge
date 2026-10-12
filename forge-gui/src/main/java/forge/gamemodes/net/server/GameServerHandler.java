package forge.gamemodes.net.server;

import forge.gamemodes.net.GameProtocolHandler;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import io.netty.channel.ChannelHandlerContext;

final class GameServerHandler extends GameProtocolHandler<IGameController> implements IHasForgeLog {

    private final FServerManager server = FServerManager.getInstance();

    GameServerHandler() {
        super(false);
    }

    private RemoteClient getClient(final ChannelHandlerContext ctx) {
        return server.getClient(ctx.channel());
    }

    @Override
    protected boolean isCurrentConnection(final ChannelHandlerContext ctx) {
        final RemoteClient client = getClient(ctx);
        return client != null && client.hasValidSlot() && client.isCurrentChannel(ctx.channel());
    }

    @Override
    protected ReplyPool getReplyPool(final ChannelHandlerContext ctx) {
        final RemoteClient client = getClient(ctx);
        return client == null ? null : client.getReplyPool(ctx.channel());
    }

    @Override
    protected IRemote getRemote(final ChannelHandlerContext ctx) {
        return getClient(ctx);
    }

    @Override
    protected void handleGuiRequestControl(final ChannelHandlerContext ctx, final Object value) {
        final Integer id = forge.gamemodes.net.GuiRequestControl.requestId(value, "ready:");
        final RemoteClient client = getClient(ctx);
        if (client != null && id != null) client.acknowledgeReady(ctx.channel(), id);
    }

    @Override
    protected IGameController getToInvoke(final ChannelHandlerContext ctx) {
        final RemoteClient client = getClient(ctx);
        return client != null ? server.getController(client.getIndex()) : null;
    }

    @Override
    protected boolean handleReply(final ChannelHandlerContext ctx, final forge.gamemodes.net.event.ReplyEvent event) {
        final RemoteClient client = getClient(ctx);
        return client != null && client.completeTimedReply(ctx.channel(), event.getIndex(), event.getReply());
    }

    @Override
    protected void beforeCall(final ChannelHandlerContext ctx, final ProtocolMethod protocolMethod, final Object[] args) {
        if (protocolMethod == ProtocolMethod.requestResync) {
            RemoteClient client = getClient(ctx);
            if (client != null) {
                IGuiGame gui = server.getGui(client.getIndex());
                if (gui instanceof RemoteClientGuiGame netGui) {
                    netLog.debug("[DeltaSync] Resync requested by client {}, deferring to game thread", client.getIndex());
                    netGui.setResyncPending();
                } else {
                    netLog.warn("[DeltaSync] GUI is not RemoteClientGuiGame, cannot resync client {}", client.getIndex());
                }
            }
        }
    }

}
