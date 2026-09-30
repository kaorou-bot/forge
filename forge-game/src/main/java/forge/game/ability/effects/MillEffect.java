package forge.game.ability.effects;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardZoneTable;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.TextUtil;

import java.util.Map;

public class MillEffect extends SpellAbilityEffect {

    @Override
    public boolean movesCardToOrFromLibrary(final SpellAbility sa) {
        return true;
    }

    @Override
    public void resolve(SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Game game = source.getGame();
        final int numCards = sa.hasParam("NumCards") ? AbilityUtils.calculateAmount(source, sa.getParam("NumCards"), sa) : 1;

        if (numCards <= 0) {
            return;
        }

        if (sa.hasParam("ForgetOtherRemembered")) {
            source.clearRemembered();
        }

        ZoneType destination = ZoneType.smartValueOf(sa.getParam("Destination"));
        if (destination == null) {
            destination = ZoneType.Graveyard;
        }

        final PlayerCollection millers = getTargetPlayers(sa);

        if (sa.hasParam("Optional")) {
            final PlayerCollection toRemove = new PlayerCollection();
            for (Player p : millers) {
                String d = destination.equals(ZoneType.Graveyard) ? "" : " (" + destination.getTranslatedName() + ")";
                final String prompt = TextUtil.concatWithSpace(Localizer.getInstance().
                        getMessage("lblDoYouWantToMill", Lang.nounWithNumeral(numCards, "card"), d));
                // CR 701.13b
                if (numCards > p.getZone(ZoneType.Library).size() || !p.getController().confirmAction(sa, null, prompt, null)) {
                    toRemove.add(p);
                }
            }
            millers.removeAll(toRemove);
        }

        Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
        final CardZoneTable table = AbilityKey.addCardZoneTableParams(moveParams, sa);
        CardCollection milled = game.getAction().mill(millers, numCards, destination, sa, moveParams);

        if (sa.hasParam("RememberMilled")) {
            sa.getHostCard().addRemembered(milled);
        }
        if (sa.hasParam("Imprint")) {
            sa.getHostCard().addImprintedCards(milled);
        }

        // run trigger if something got milled
        table.triggerChangesZoneAll(game, sa);
    }

    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        final int numCards = sa.hasParam("NumCards") ? AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("NumCards"), sa) : 1;
        final boolean optional = sa.hasParam("Optional");
        final boolean eachP = sa.hasParam("Defined") && sa.getParam("Defined").equals("Player");
        String each = "Each player";
        final PlayerCollection tgtPs = getTargetPlayers(sa);

        if (sa.hasParam("IfDesc")) {
            final String ifD = sa.getParam("IfDesc");
            if (ifD.equals("True")) {
                String ifDesc = sa.getDescription();
                if (ifDesc.contains(",")) {
                    sb.append(ifDesc, 0, ifDesc.indexOf(",") + 1);
                } else {
                    sb.append("[MillEffect IfDesc parsing error]");
                }
            } else {
                sb.append(ifD);
            }
            sb.append(" ");
            each = each.toLowerCase();
        }

        final ZoneType dest = ZoneType.smartValueOf(sa.getParam("Destination"));
        if (dest == null || dest == ZoneType.Graveyard) {
            // Only the verb/amount is generated here; preserve any preceding IfDesc clause.
            final String players = eachP ? stackText(sa.hasParam("IfDesc") ? "EachPlayerLower" : "EachPlayer") : stackList(tgtPs);
            final String amount = numCards == 1 ? stackText("OneCard")
                    : stackText("Cards", Lang.getNumeral(numCards), numCards);
            sb.append(stackText(optional ? "MayMill" : eachP || tgtPs.size() <= 1 ? "Mill" : "MillMany",
                    players, amount));
            return sb.toString();
        }
        sb.append(eachP ? each : Lang.joinHomogenous(tgtPs));
        sb.append(" ");
        sb.append(optional ? "may " : "");
        if ((dest == null) || dest.equals(ZoneType.Graveyard)) {
            sb.append("mill");
        } else if (dest.equals(ZoneType.Ante)) {
            sb.append("ante");
        }
        sb.append((optional || tgtPs.size() > 1) && !eachP ? " " : "s ");

        sb.append(Lang.nounWithNumeralExceptOne(numCards, "card")).append(".");

        return sb.toString();
    }

}
