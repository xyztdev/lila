package lila
package security

import user.User

import play.api.mvc.RequestHeader
import com.mongodb.casbah.MongoCollection
import com.mongodb.casbah.query.Imports._
import org.joda.time.DateTime
import scalaz.effects._

case class UserSpy(ips: List[String], uas: List[String])

final class Store(collection: MongoCollection) {

  def save(sessionId: String, username: String, req: RequestHeader) {
    collection += DBObject(
      "_id" -> sessionId,
      "user" -> normalize(username),
      "ip" -> ip(req),
      "ua" -> ua(req),
      "date" -> DateTime.now,
      "up" -> true)
  }

  def getUsername(sessionId: String): Option[String] = for {
    obj ← collection.findOneByID(
      sessionId,
      DBObject("user" -> true, "up" -> true))
    up ← obj.getAs[Boolean]("up")
    if up
    user ← obj.getAs[String]("user")
  } yield user

  def delete(sessionId: String) {
    collection.update(
      DBObject("_id" -> sessionId),
      $set("up" -> false))
  }

  // useful when closing an account,
  // we want to logout too
  def deleteUsername(username: String) {
    collection.update(
      DBObject("user" -> normalize(username)),
      $set("up" -> false))
  }

  def userSpy(username: String): IO[UserSpy] = io {
    collection.find(DBObject("user" -> normalize(username))).toList
  } map { objs ⇒
    val ips = objs.map(_.getAs[String]("ip")).flatten.distinct
    val uas = objs.map(_.getAs[String]("ua")).flatten.distinct
    UserSpy(ips, uas)
  }

  def explore(username: String, withKnown: Set[String] = Set.empty): Set[String] = {
    val known = withKnown + username
    val children = newSiblings(username, known)
    children.foldLeft(children) {
      case (siblings, child) ⇒ siblings ++ explore(child, known ++ siblings)
    }
  }

  private def newSiblings(username: String, without: Set[String]): Set[String] =
    userIps(username).flatMap(usernamesByIp) diff without

  private def userIps(username: String): Set[String] =
    collection.find(
      DBObject("user" -> normalize(username)),
      DBObject("ip" -> true)
    ).toList.map(_.getAs[String]("ip")).flatten.toSet

  private def usernamesByIp(ip: String): Set[String] =
    collection.find(
      DBObject("ip" -> ip),
      DBObject("user" -> true)
    ).toList.map(_.getAs[String]("user")).flatten.toSet

  private def ip(req: RequestHeader) = req.remoteAddress

  private def ua(req: RequestHeader) = req.headers.get("User-Agent") | "?"

  private def normalize(username: String) = username.toLowerCase
}
