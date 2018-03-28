package cs.brown.edu.aelp.pokemmo.battle.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cs.brown.edu.aelp.pokemmo.battle.Arena;
import cs.brown.edu.aelp.pokemmo.battle.Battle;
import cs.brown.edu.aelp.pokemmo.battle.action.FightTurn;
import cs.brown.edu.aelp.pokemmo.battle.action.NullTurn;
import cs.brown.edu.aelp.pokemmo.battle.action.Turn;
import cs.brown.edu.aelp.pokemmo.battle.events.AttackEvent;
import cs.brown.edu.aelp.pokemmo.battle.events.EndOfTurnEvent;
import cs.brown.edu.aelp.pokemmo.battle.events.StartOfTurnEvent;
import cs.brown.edu.aelp.pokemmo.pokemon.Pokemon;
import cs.brown.edu.aelp.pokemmo.pokemon.moves.MoveResult;
import cs.brown.edu.aelp.pokemmo.trainer.Trainer;

public class PvPBattle extends Battle {

  private final Trainer a;

  private final Trainer b;

  private Map<Trainer, Turn> turnsMap = new HashMap<>();

  public PvPBattle(Arena arena, Trainer a, Trainer b) {
    super(arena);
    this.a = a;
    this.b = b;

    setBattleState(BattleState.WAITING);
  }

  public void evaluate() {
    if (!getBattleState().equals(BattleState.READY)) {
      throw new RuntimeException("Not in ready state!");
    }

    List<Turn> turns = new ArrayList<>(turnsMap.values());
    turns.sort(this::turnComparator);

    boolean stop = false;

    for (Turn turn : turns) {
      // start of turn
      if (stop) {
        break;
      }

      turn.getTrainer().getEffectSlot()
          .handle(new StartOfTurnEvent(this, turn));
    }

    for (Turn turn : turns) {
      // turn
      if (stop) {
        break;
      }

      if (turn instanceof NullTurn) {
        handleTurn((NullTurn) turn);
      } else if (turn instanceof FightTurn) {
        handleTurn((FightTurn) turn);
      } else {
        handleTurn(turn);
      }
    }

    for (Turn turn : turns) {
      // end of turn

      if (stop) {
        break;
      }

      // Clean this up:
      turn.getTrainer().getEffectSlot().handle(new EndOfTurnEvent(this, turn));
    }

    turnsMap.clear();

    setBattleState(BattleState.WAITING);
  }

  private void handleTurn(Turn turn) {
    throw new IllegalArgumentException("No turn handler!");
  }

  private void handleTurn(NullTurn turn) {
    System.out.println("Null turn");
  }

  public void handleTurn(FightTurn turn) {
    System.out.println("Fight turn");

    Trainer atkTrainer = turn.getTrainer();
    Trainer defTrainer = other(atkTrainer);

    AttackEvent atkEvent = new AttackEvent(this, atkTrainer,
        atkTrainer.getActivePokemon(), defTrainer,
        defTrainer.getActivePokemon());

    // Attacking event
    atkTrainer.getEffectSlot().handle(atkEvent);
    atkTrainer.getActivePokemon().getEffectSlot().handle(atkEvent);

    MoveResult result = turn.getMove().getResult(atkEvent);
    // Todo: defending events

    System.out.println(result);

    if (result.getOutcome().equals(MoveResult.MoveOutcome.HIT)) {
      // Other events...

      Pokemon defendingPokemon = defTrainer.getActivePokemon();

      defendingPokemon
          .setHealth(defendingPokemon.getBaseHealth() - result.getDamage());

      if (defendingPokemon.isKnockedOut()) {
        System.out.println("KO!");
      }
    }
  }

  public void victory(Trainer t) {
    System.out.println("Victory for: " + t.getId());

    setBattleState(BattleState.DONE);
  }

  public void setTurn(Turn t) {

    if (!getBattleState().equals(BattleState.WAITING)) {
      throw new RuntimeException("Not in waiting state!");
    }

    turnsMap.put(t.getTrainer(), t);

    if (turnsMap.size() == 2) {
      setBattleState(BattleState.READY);
    }
  }

  private Trainer other(Trainer t) {
    if (t.equals(a)) {
      return b;
    }

    return a;
  }
}
