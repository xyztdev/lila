package lila
package ai

import chess.{ Game, Move }
import game.DbGame

import scalaz.effects._
import akka.dispatch.Future

final class StupidAi extends Ai with core.Futuristic {

  def play(dbGame: DbGame, initialFen: Option[String]): Future[Valid[(Game, Move)]] = Future {

    val game = dbGame.toChess

    for {
      destination ← game.situation.destinations.headOption toValid "Game is finished"
      (orig, dests) = destination
      dest ← dests.headOption toValid "No moves from " + orig
      newChessGameAndMove ← game(orig, dest)
    } yield newChessGameAndMove
  }

  def analyse(dbGame: DbGame, initialFen: Option[String]) = 
    throw new RuntimeException("Stupid analysis is not implemented")
}
