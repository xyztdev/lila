package lila
package tournament

import org.joda.time.{ DateTime, Duration }
import org.scala_tools.time.Imports._
import com.novus.salat.annotations.Key
import com.mongodb.casbah.query.Imports.DBObject
import ornicar.scalalib.Random
import scalaz.NonEmptyList

import user.User
import game.PovRef

case class Data(
  name: String,
  clock: TournamentClock,
  minutes: Int,
  minPlayers: Int,
  createdAt: DateTime,
  createdBy: String)

sealed trait Tournament {

  val id: String
  val data: Data
  def encode: RawTournament
  def players: Players
  def winner: Option[Player]
  def pairings: List[Pairing]
  def isRunning: Boolean = false
  def isFinished: Boolean = false

  def numerotedPairings: Seq[(Int, Pairing)] = (pairings.size to 1 by -1) zip pairings

  def name = data.name
  def nameT = name + " tournament"

  def clock = data.clock
  def minutes = data.minutes
  lazy val duration = new Duration(minutes * 60 * 1000)

  def userIds = players map (_.id)
  def activeUserIds = players filter (_.active) map (_.id)
  def nbPlayers = players.size
  def minPlayers = data.minPlayers
  def playerRatio = "%d/%d".format(nbPlayers, minPlayers)
  def contains(userId: String): Boolean = userIds contains userId
  def contains(user: User): Boolean = contains(user.id)
  def contains(user: Option[User]): Boolean = user.fold(contains, false)
  def isActive(userId: String): Boolean = activeUserIds contains userId
  def isActive(user: User): Boolean = isActive(user.id)
  def isActive(user: Option[User]): Boolean = user.fold(isActive, false)
  def missingPlayers = minPlayers - players.size

  def createdBy = data.createdBy
  def createdAt = data.createdAt
}

sealed trait StartedOrFinished extends Tournament {

  def startedAt: DateTime

  def rankedPlayers = (1 to players.size) zip players

  def winner = players.headOption
  def winnerUserId = winner map (_.username)

  def encode(status: Status) = new RawTournament(
    id = id,
    status = status.id,
    name = data.name,
    clock = data.clock,
    minutes = data.minutes,
    minPlayers = data.minPlayers,
    createdAt = data.createdAt,
    createdBy = data.createdBy,
    startedAt = startedAt.some,
    players = players,
    pairings = pairings map (_.encode))

  def finishedAt = startedAt + duration
}

case class Created(
    id: String,
    data: Data,
    players: Players) extends Tournament {

  import data._

  def readyToStart = players.size >= minPlayers

  def isEmpty = players.isEmpty

  def pairings = Nil

  def winner = None

  def encode = new RawTournament(
    id = id,
    status = Status.Created.id,
    name = data.name,
    clock = data.clock,
    minutes = data.minutes,
    minPlayers = data.minPlayers,
    createdAt = data.createdAt,
    createdBy = data.createdBy,
    players = players)

  def join(user: User): Valid[Created] = contains(user).fold(
    !!("User %s is already part of the tournament" format user.id),
    withPlayers(players :+ Player(user)).success
  )

  def withdraw(user: User): Valid[Created] = contains(user).fold(
    withPlayers(players filterNot (_ is user)).success,
    !!("User %s is not part of the tournament" format user.id)
  )

  private def withPlayers(s: Players) = copy(players = s)

  def start = Started(id, data, DateTime.now, players, Nil)
}

case class Started(
    id: String,
    data: Data,
    startedAt: DateTime,
    players: Players,
    pairings: List[Pairing]) extends StartedOrFinished {

  override def isRunning = true

  def addPairings(ps: NonEmptyList[Pairing]) =
    copy(pairings = ps.list ::: pairings)

  def updatePairing(gameId: String, f: Pairing ⇒ Pairing) = copy(
    pairings = pairings map { p ⇒ (p.gameId == gameId).fold(f(p), p) }
  )

  def readyToFinish = remainingSeconds == 0

  def remainingSeconds: Int = math.max(0, finishedAt.getSeconds - nowSeconds).toInt

  def clockStatus = "%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60)

  def userCurrentPov(userId: String): Option[PovRef] = {
    pairings filter (_.playing) map { _ povRef userId }
  }.flatten.headOption
  def userCurrentPov(user: Option[User]): Option[PovRef] =
    user.fold(u ⇒ userCurrentPov(u.id), none)

  def finish = refreshPlayers |> { tour ⇒
    Finished(
      id = tour.id,
      data = tour.data,
      startedAt = tour.startedAt,
      players = players,
      pairings = tour.pairings)
  }

  def withdraw(user: User): Valid[Started] = contains(user).fold(
    withPlayers(players map {
      case p if p is user ⇒ p.doWithdraw
      case p              ⇒ p
    }).success,
    !!("User %s is not part of the tournament" format user.id)
  )

  private def withPlayers(s: Players) = copy(players = s)

  private def refreshPlayers = withPlayers(Player refresh this)

  def encode = refreshPlayers.encode(Status.Started)
}

case class Finished(
    id: String,
    data: Data,
    startedAt: DateTime,
    players: Players,
    pairings: List[Pairing]) extends StartedOrFinished {

  override def isFinished = true

  def encode = encode(Status.Finished)
}

case class RawTournament(
    @Key("_id") id: String,
    name: String,
    clock: TournamentClock,
    minutes: Int,
    minPlayers: Int,
    createdAt: DateTime,
    createdBy: String,
    status: Int,
    startedAt: Option[DateTime] = None,
    players: List[Player] = Nil,
    pairings: List[RawPairing] = Nil) {

  def created: Option[Created] = (status == Status.Created.id) option Created(
    id = id,
    data = Data(name, clock, minutes, minPlayers, createdAt, createdBy),
    players = players)

  def started: Option[Started] = for {
    stAt ← startedAt
    if status == Status.Started.id
  } yield Started(
    id = id,
    data = Data(name, clock, minutes, minPlayers, createdAt, createdBy),
    startedAt = stAt,
    players = players,
    pairings = decodePairings)

  def finished: Option[Finished] = for {
    stAt ← startedAt
    if status == Status.Finished.id
  } yield Finished(
    id = id,
    data = Data(name, clock, minutes, minPlayers, createdAt, createdBy),
    startedAt = stAt,
    players = players,
    pairings = decodePairings)

  def decodePairings = pairings map (_.decode) flatten

  def any: Option[Tournament] = Status(status) flatMap {
    case Status.Created  ⇒ created
    case Status.Started  ⇒ started
    case Status.Finished ⇒ finished
  }
}

object Tournament {

  import lila.core.Form._

  def apply(
    createdBy: User,
    clock: TournamentClock,
    minutes: Int,
    minPlayers: Int): Created = Created(
    id = Random nextString 8,
    data = Data(
      name = RandomName(),
      clock = clock,
      createdBy = createdBy.id,
      createdAt = DateTime.now,
      minutes = minutes,
      minPlayers = minPlayers),
    players = List(Player(createdBy))
  )

  val clockTimes = 0 to 10 by 1
  val clockTimeDefault = 2
  val clockTimeChoices = options(clockTimes, "%d minute{s}")

  val clockIncrements = 0 to 5 by 1
  val clockIncrementDefault = 0
  val clockIncrementChoices = options(clockIncrements, "%d second{s}")

  val minutes = 5 to 60 by 5
  val minuteDefault = 10
  val minuteChoices = options(minutes, "%d minute{s}")

  val minPlayers = (3 to 4) ++ (5 to 30 by 5)
  val minPlayerDefault = 10
  val minPlayerChoices = options(minPlayers, "%d player{s}")
}
