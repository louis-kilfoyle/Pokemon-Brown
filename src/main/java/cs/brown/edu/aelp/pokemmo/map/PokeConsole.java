package cs.brown.edu.aelp.pokemmo.map;

import cs.brown.edu.aelp.networking.PacketSender;
import cs.brown.edu.aelp.pokemmo.data.authentication.User;

public class PokeConsole extends Entity {

  public PokeConsole(Location loc) {
    super(loc);
  }

  @Override
  public void interact(User u) {
    PacketSender.sendOpenPokeConsolePacket(u);
  }

}