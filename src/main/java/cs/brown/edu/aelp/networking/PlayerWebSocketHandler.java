package cs.brown.edu.aelp.networking;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cs.brown.edu.aelp.networking.Trade.TRADE_STATUS;
import cs.brown.edu.aelp.pokemmo.battle.Battle;
import cs.brown.edu.aelp.pokemmo.battle.Battle.BattleState;
import cs.brown.edu.aelp.pokemmo.battle.BattleManager;
import cs.brown.edu.aelp.pokemmo.battle.Item;
import cs.brown.edu.aelp.pokemmo.battle.Item.ItemType;
import cs.brown.edu.aelp.pokemmo.battle.action.FightTurn;
import cs.brown.edu.aelp.pokemmo.battle.action.ItemTurn;
import cs.brown.edu.aelp.pokemmo.battle.action.SwitchTurn;
import cs.brown.edu.aelp.pokemmo.battle.action.Turn;
import cs.brown.edu.aelp.pokemmo.data.DataSource.AuthException;
import cs.brown.edu.aelp.pokemmo.data.authentication.User;
import cs.brown.edu.aelp.pokemmo.data.authentication.UserManager;
import cs.brown.edu.aelp.pokemmo.map.Chunk;
import cs.brown.edu.aelp.pokemmo.map.Chunk.CHUNK_TYPE;
import cs.brown.edu.aelp.pokemmo.map.Entity;
import cs.brown.edu.aelp.pokemmo.map.Location;
import cs.brown.edu.aelp.pokemmo.map.Path;
import cs.brown.edu.aelp.pokemmo.map.PokeConsole;
import cs.brown.edu.aelp.pokemmo.pokemon.Pokemon;
import cs.brown.edu.aelp.pokemmo.pokemon.moves.Move;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class PlayerWebSocketHandler {

  private static final Gson GSON = new Gson();

  public static enum MESSAGE_TYPE {
    CONNECT,
    INITIALIZE,
    GAME_PACKET,
    PLAYER_REQUEST_PATH,
    ENCOUNTERED_POKEMON,
    TRADE,
    START_BATTLE,
    END_BATTLE,
    BATTLE_TURN_UPDATE,
    CLIENT_BATTLE_UPDATE,
    CHAT,
    SERVER_MESSAGE,
    CHALLENGE, // 12
    CHALLENGE_RESPONSE,
    UPDATE_ACTIVE_POKEMON,
    UPDATE_TEAM,
    OPEN_POKE_CONSOLE,
    BUY_ITEM
  }

  public static enum OP_CODES {
    ENTERED_CHUNK,
    LEFT_CHUNK,
    ENTERED_BATTLE,
    LEFT_BATTLE,
    CHAT
  }

  // used for battle moves

  public static enum ACTION_TYPE {
    RUN,
    SWITCH,
    USE_ITEM,
    FIGHT
  }

  public static enum TURN_STATE {
    NORMAL,
    MUST_SWITCH
  };

  private static final MESSAGE_TYPE[] MESSAGE_TYPES = MESSAGE_TYPE.values();
  private static final ACTION_TYPE[] ACTION_TYPES = ACTION_TYPE.values();

  @OnWebSocketConnect
  public void onConnect(Session session) throws Exception {
    // do we actually need to do anything here?
  }

  @OnWebSocketClose
  public void onClose(Session session, int statusCode, String reason) {
    for (User u : UserManager.getAllUsers()) {
      // don't use .isConnected() because obviously their session is already
      // closed; read the method name, idiot
      if (u.getSession() != null && session == u.getSession()) {
        u.disconnectCleanup();
        System.out.printf("%s (%d) disconnected.%n", u.getUsername(),
            u.getId());
      }
    }
  }

  @OnWebSocketMessage
  public void onMessage(Session session, String message) {
    // we have received a websocket message
    // this message will be a JSON object (in string form)
    JsonObject received = GSON.fromJson(message, JsonObject.class);
    JsonObject payload = received.getAsJsonObject("payload");

    switch (MESSAGE_TYPES[received.get("type").getAsInt()]) {
    case CONNECT:
      handleConnect(session, payload);
      break;
    case PLAYER_REQUEST_PATH:
      handlePath(session, payload);
      break;
    case TRADE:
      handleTrade(session, payload);
      break;
    case CLIENT_BATTLE_UPDATE:
      handleClientBattleUpdate(session, payload);
      break;
    case CHAT:
      handleChat(session, payload);
      break;
    case CHALLENGE:
      handleChallenge(session, payload);
      break;
    case CHALLENGE_RESPONSE:
      handleChallengeResponse(session, payload);
      break;
    case UPDATE_ACTIVE_POKEMON:
      handleUpdateActivePokemon(session, payload);
      break;
    case UPDATE_TEAM:
      handleUpdateTeam(session, payload);
      break;
    case BUY_ITEM:
      handleBuyItem(session, payload);
      break;
    default:
      // something went wrong, we got an unknown message type
    }

  }

  private static void handleConnect(Session session, JsonObject payload) {
    // authenticate this session
    int id = payload.get("id").getAsInt();
    String token = payload.get("token").getAsString();
    try {
      User u = UserManager.authenticate(id, token);
      u.setSession(session);
      PacketSender.sendInitializationPacket(u);
      System.out.println(u.getUsername() + " connected.");
      // TODO: Inform all other users of their connection?
    } catch (AuthException e1) {
      // their credentials were bad or something went wrong
      session.close();
    }
  }

  private static void handlePath(Session session, JsonObject payload) {
    // TODO: Actually verify the path, maybe?...
    int id = payload.get("id").getAsInt();
    User u = UserManager.getUserById(id);
    if (u == null || u.getSession() != session) {
      session.close();
      return;
    }
    if (u.isBusy()) {
      return;
    }
    JsonArray path = payload.getAsJsonArray("path");
    Chunk c = u.getLocation().getChunk();
    List<Location> locs = new ArrayList<>();
    for (JsonElement o : path) {
      JsonObject tile = (JsonObject) o;
      Location loc = new Location(c, tile.get("row").getAsInt(),
          tile.get("col").getAsInt());
      locs.add(loc);
    }
    u.setPath(new Path(locs, locs.get(0).getChunk().getEntities(u)));
  }

  private static void handleTrade(Session session, JsonObject payload) {
    int me_id = payload.get("id").getAsInt();
    int other_id = payload.get("other_id").getAsInt();
    User me = UserManager.getUserById(me_id);
    User other = UserManager.getUserById(other_id);
    if (me == null || me.getSession() != session) {
      session.close();
      return;
    }
    System.out.printf("%s sent a trade packet.%n", me.getUsername());
    if (me_id == other_id) {
      System.out.printf("%s send a cancel trade packet.%n", me.getUsername());
      if (me.getActiveTrade() != null) {
        System.out.printf("Canceling his trade%n");
        Trade t = me.getActiveTrade();
        t.setStatus(TRADE_STATUS.CANCELED);
        PacketSender.sendTradePacket(t.other(me), t);
        me.setActiveTrade(null);
        t.other(me).setActiveTrade(null);
      }
      return;
    }
    if (other == null || !other.isConnected()) {
      System.out.printf("other is null or disconnected, canceling%n");
      // dummy trade for canceling
      Trade t = new Trade(me, me);
      t.setStatus(TRADE_STATUS.CANCELED);
      PacketSender.sendTradePacket(me, t);
      me.setActiveTrade(null);
      return;
    }
    if (other.isBusy() && (other.getActiveTrade() == null
        || !other.getActiveTrade().involves(me))) {
      System.out.printf("%s is busy%n", other.getUsername());
      // dummy trade for busy
      Trade t = new Trade(me, other);
      t.setStatus(TRADE_STATUS.BUSY);
      PacketSender.sendTradePacket(me, t);
      me.setActiveTrade(null);
      return;
    }
    if (me.getActiveTrade() != null && !me.getActiveTrade().involves(other)) {
      me.kick();
      return;
    }
    Trade t = me.getActiveTrade();
    if (t == null) {
      System.out.printf("Active trade was null, creating a new one%n");
      t = new Trade(me, other);
      me.setActiveTrade(t);
      other.setActiveTrade(t);
      PacketSender.sendTradePacket(other, t);
      return;
    }
    System.out.println("Made it past all initial checks");
    boolean me_accepted = payload.get("me_accepted").getAsBoolean();
    int me_curr = payload.get("me_currency").getAsInt();
    Set<Integer> me_pokemon = new HashSet<>();
    JsonArray pokemon = payload.get("me_pokemon").getAsJsonArray();
    for (JsonElement o : pokemon) {
      me_pokemon.add(o.getAsInt());
    }
    boolean isUser1 = t.getUser1().getId() == me.getId();
    if (!t.setCurrency(me_curr, isUser1)
        || !t.setPokemon(me_pokemon, isUser1)) {
      t.setStatus(TRADE_STATUS.CANCELED);
      me.kick();
      System.out.printf(
          "WARNING: %s tried to trade pokemon or currency that they don't have.%n",
          me.getUsername());
    }
    System.out.println("Same trade?: " + t.isSameTrade(payload, isUser1));
    if (!me_accepted) {
      t.invalidate();
    } else if (t.isSameTrade(payload, isUser1)) {
      System.out.printf("Setting accepted for %s%n.", me.getUsername());
      t.setAccepted(isUser1);
    }
    if (t.getStatus() != TRADE_STATUS.COMPLETE
        && t.getStatus() != TRADE_STATUS.FAILED) {
      System.out.println("Not yet complete.");
      PacketSender.sendTradePacket(me, t);
      PacketSender.sendTradePacket(other, t);
    }
  }

  private static void handleClientBattleUpdate(Session session,
      JsonObject payload) {

    int turnId = payload.get("turn_id").getAsInt();

    int id = payload.get("id").getAsInt();
    User user = UserManager.getUserById(id);
    if (!user.isConnected() || !user.getSession().equals(session)) {
      System.err.println("Bad Session");
      session.close();
      return;
    }

    Battle battle = user.getCurrentBattle();

    Turn t = null;
    switch (ACTION_TYPES[payload.get("action").getAsInt()]) {
    case RUN:
      // TODO: run
      if (!battle.getBattleState().equals(BattleState.DONE)) {
        battle.forfeit(user);
      }
      return;
    case SWITCH:
      // TODO: switch
      Integer switchId = payload.get("switchId").getAsInt();
      System.out.println("Switch ID: " + switchId);

      if (switchId == user.getActivePokemon().getId()) {
        break;
      }

      for (Pokemon p : user.getTeam()) {
        if (p.getId() == switchId) {
          t = new SwitchTurn(user, p, user.getActivePokemon());
          break;
        }
      }
      break;
    case USE_ITEM:
      Integer itemId = payload.get("itemId").getAsInt();

      int quantity = user.getInventory().getItemAmount(itemId);
      if (quantity == 0) {
        return;
      }

      t = new ItemTurn(user, new Item(itemId));

      break;
    case FIGHT:

      Integer moveId = payload.get("moveId").getAsInt();
      System.out.println("Move ID: " + moveId);
      List<Move> moves = user.getActivePokemon().getMoves();
      for (Move m : moves) {
        // System.out.println(m);
        if (m.getId() == moveId && m.getCurrPP() > 0) {
          t = new FightTurn(user, m);
          break;
        }
      }

      break;
    default:
      System.out.println("ERROR: Invalid packet sent to battle handler.");
      user.kick();
      return;
    }

    System.out.println(battle.getBattleState());

    synchronized (battle) {

      if (t == null || !battle.getBattleState().equals(BattleState.WAITING)) {
        System.err.println("Not waiting");
        user.kick();
        return;
      }

      if (!battle.setTurn(t)) {
        System.err.println("Bad Turn");
        user.kick();
        return;
      }

      if (battle.getBattleState().equals(BattleState.READY)) {
        battle.evaluate();

        System.out.println(battle.dbgStatus());
      }

    }
  }

  private static void handleChat(Session session, JsonObject payload) {
    int id = payload.get("id").getAsInt();
    User u = UserManager.getUserById(id);
    if (u == null || u.getSession() != session) {
      session.close();
      return;
    }
    String msg = payload.get("message").getAsString();
    if (msg.length() == 0) {
      return;
    }
    // client will sanitize to avoid injection
    JsonObject chat = PacketSender.buildPlayerOpMessage(u, OP_CODES.CHAT);
    chat.addProperty("message", msg);
    PacketSender.queueOpForChunk(chat, u.getLocation().getChunk());
  }

  private static void handleChallenge(Session session, JsonObject payload) {
    int id = payload.get("id").getAsInt();
    User u1 = UserManager.getUserById(id);
    if (u1 == null || u1.getSession() != session) {
      session.close();
      return;
    }
    CHUNK_TYPE t = u1.getLocation().getChunk().getType();
    if (t == CHUNK_TYPE.HEAL || t == CHUNK_TYPE.PASSIVE) {
      PacketSender.sendChallengeResponse(u1, "disabled");
      return;
    }
    if (u1.getChallenge() != null) {
      String reason = u1.getChallenge().getTo() == u1 ? "denied" : "canceled";
      PacketSender.sendChallengeResponse(u1.getChallenge().other(u1), reason);
      u1.getChallenge().cancel();
    }
    if (!payload.has("challenged_id")) {
      return;
    }
    int challenged = payload.get("challenged_id").getAsInt();
    User u2 = UserManager.getUserById(challenged);
    double dist = u1.getLocation().dist(u2.getLocation());
    if (dist < 0 || dist > 8) {
      return;
    }
    if (u2 == null || u2.isBusy()) {
      PacketSender.sendChallengeResponse(u1, "busy");
      return;
    }
    // we actually don't need to do anything with this object, even tho it feels
    // weird
    new Challenge(u1, u2);
    PacketSender.sendBattleChallenge(u2, u1);
  }

  private static void handleChallengeResponse(Session session,
      JsonObject payload) {
    int id = payload.get("id").getAsInt();
    User u1 = UserManager.getUserById(id);
    if (u1 == null || u1.getSession() != session) {
      session.close();
      return;
    }
    if (u1.getChallenge() == null || u1.getChallenge().getTo() != u1) {
      return;
    }
    boolean accepted = payload.get("accepted").getAsBoolean();
    if (!accepted) {
      PacketSender.sendChallengeResponse(u1.getChallenge().getFrom(), "denied");
      u1.getChallenge().cancel();
      return;
    }
    User u2 = u1.getChallenge().getFrom();
    u1.getChallenge().cancel();
    BattleManager.getInstance().createPvPBattle(u1, u2);
  }

  private static void handleUpdateActivePokemon(Session session,
      JsonObject payload) {
    int id = payload.get("id").getAsInt();
    User u = UserManager.getUserById(id);
    if (u == null || u.getSession() != session) {
      session.close();
      return;
    }
    int poke_id = payload.get("pokemon_id").getAsInt();
    Pokemon p = u.getPokemonById(poke_id);

    if (p != null && u.getTeam().contains(p)) {
      u.setActivePokemon(p);
    } else {
      u.kick();
      return;
    }
  }

  private static void handleUpdateTeam(Session session, JsonObject payload) {
    int id = payload.get("id").getAsInt();
    User u = UserManager.getUserById(id);
    if (u == null || u.getSession() != session) {
      session.close();
      return;
    }
    Chunk c = u.getLocation().getChunk();
    boolean allowed = false;
    for (Entity e : c.getEntities()) {
      if (e instanceof PokeConsole) {
        double dist = e.getLocation().dist(u.getLocation());
        if (dist < 3) {
          allowed = true;
        }
      }
    }
    if (!allowed) {
      u.kick();
      return;
    }
    JsonArray arr = payload.get("pokemon").getAsJsonArray();
    if (arr.size() == 0 || arr.size() > 5) {
      return;
    }
    u.emptyTeam();
    for (JsonElement e : payload.get("pokemon").getAsJsonArray()) {
      int poke_id = e.getAsInt();
      Pokemon p = u.getPokemonById(poke_id);
      if (p == null) {
        u.kick();
        return;
      }
      u.addPokemonToTeam(p);
      u.removeInactivePokemon(p);
      p.setStored(false);
    }
  }

  private static void handleBuyItem(Session session, JsonObject payload) {
    int id = payload.get("id").getAsInt();
    User u = UserManager.getUserById(id);
    if (u == null || u.getSession() != session) {
      session.close();
      return;
    }
    if (u.getLocation().getChunk().getType() != CHUNK_TYPE.HEAL) {
      u.sendMessage("You can only buy items in the PokeCenter!");
      return;
    }
    int item_id = payload.get("item_id").getAsInt();
    if (item_id >= ItemType.values().length) {
      u.kick();
      System.out.printf("%s tried to buy an invalid item: %d%n",
          u.getUsername(), item_id);
      return;
    }
    ItemType t = ItemType.values()[item_id];
    int cost = Item.getCost(t);
    int amt = payload.get("quantity").getAsInt();
    int total = cost * amt;
    // overflow?! :O
    assert total >= 0;
    if (total > u.getCurrency()) {
      u.kick();
      System.out.printf("%s tried to buy more than they could afford.%n",
          u.getUsername());
      return;
    }
    u.setCurrency(u.getCurrency() - total);
    u.getInventory().addItems(item_id, amt);
    System.out.printf("%s bought %d of item ID %s%n.", u.getUsername(), amt,
        t.toString());
  }
}
