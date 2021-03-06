package lila
package tournament

import game.{ GameRepo, DbGame }
import user.User
import core.Settings
import security.Flood
import socket.History
import memo.MonoMemo

import com.traackr.scalastic.elasticsearch
import com.mongodb.casbah.MongoCollection
import scalaz.effects._
import akka.actor.Props
import play.api.libs.concurrent._
import play.api.Application

final class TournamentEnv(
    app: Application,
    settings: Settings,
    getUser: String => IO[Option[User]],
    gameRepo: GameRepo,
    timelinePush: DbGame ⇒ IO[Unit],
    flood: Flood,
    siteSocket: site.Socket,
    mongodb: String ⇒ MongoCollection) {

  implicit val ctx = app
  import settings._

  lazy val forms = new DataForm

  lazy val repo = new TournamentRepo(
    collection = mongodb(TournamentCollectionTournament))

  lazy val api = new TournamentApi(
    repo = repo,
    gameRepo = gameRepo, 
    getUser = getUser,
    timelinePush = timelinePush,
    socket = socket,
    siteSocket = siteSocket)

  lazy val roomRepo = new RoomRepo(
    collection = mongodb(TournamentCollectionRoom)
  )

  lazy val messenger = new Messenger(
    roomRepo = roomRepo, 
    getTournament = repo.byId,
    getUser = getUser)

  lazy val socket = new Socket(
    getTournament = repo.byId,
    hubMaster = hubMaster,
    messenger = messenger,
    flood = flood)

  lazy val history = () ⇒ new History(timeout = TournamentMessageLifetime)

  lazy val hubMaster = Akka.system.actorOf(Props(new HubMaster(
    makeHistory = history,
    messenger = messenger,
    uidTimeout = TournamentUidTimeout,
    hubTimeout = TournamentHubTimeout
  )), name = ActorTournamentHubMaster)

  lazy val organizer = Akka.system.actorOf(Props(new Organizer(
    repo = repo,
    api = api,
    hubMaster = hubMaster
  )), name = ActorTournamentOrganizer)

  def tournamentIds = tournamentIdsMemo.apply

  private lazy val tournamentIdsMemo = 
    new MonoMemo(TournamentMemoTtl, repo.inProgressIds)
}
