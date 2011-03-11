package edu.luc.cs.webservices.unfiltered.bookmarks

import unfiltered.filter._
import unfiltered.request._
import unfiltered.response._

import org.clapper.avsl.Logger

import collection.immutable.Map
import java.util.Date

object rootPlan extends Planify ({
  case GET(Path(Seg(Nil))) => {
	Logger(rootPlan getClass).debug("GET /")
	Ok ~> ResponseString(
      "To register: " +
      "curl -X PUT -d 'user[password]=pass' -d 'user[email]=you@host' -d 'user[full_name]=Your%20Name' -v http://localhost:8080/users/you"
    )
  }
})

abstract class UserRepositoryPlan(val userRepository: UserRepository) extends Plan {
  val authSvc = new UserRepositoryAuthService(userRepository)
  def verify(u: String, p: String, user: User) = authSvc.verify(u, p) && user.name == u
  def Fail(name: String) = Unauthorized ~> WWWAuthenticate("""Basic realm="/""" + name + "\"")
}

class UserPlan(override val userRepository: UserRepository, val renderer: Renderer[User]) 
extends UserRepositoryPlan(userRepository) {
  val logger = Logger(classOf[UserPlan])

  def storeUserFromForm(name: String, form: Map[String, Seq[String]]) = {
    val user = User(name, 
    		        form("user[password]")(0), 
    		        form("user[email]")(0), 
    		        form("user[full_name]")(0))
    userRepository.store(user)
    user
  }

  def intent = {
    case req @ GET(Path(Seg("users" :: name :: Nil))) => {
      logger.debug("GET /users/%s" format name)
      userRepository findByName name match {
        case Some(user) => Ok ~> renderer(req)(user)
        case _ => NotFound
      }
    }
    
    case req @ PUT(Path(Seg("users" :: name :: Nil))) => try {
      logger.debug("PUT /users/%s" format name)
      userRepository findByName name match {
        case Some(user) => req match {
          case BasicAuth(u, p) if verify(u, p, user) => {
            val Params(form) = req 
            storeUserFromForm(name, form)
            NoContent
          }
          case _ => Fail(name)
        }
        case _ => {
          val Params(form) = req
          val user = storeUserFromForm(name, form)
          Created ~> renderer(req)(user)
        }
      }
    } catch { case _ => BadRequest }
    
    case req @ DELETE(Path(Seg("users" :: name :: Nil))) => {
      logger.debug("DELETE /users/%s" format name)
      userRepository findByName name match {
        case Some(user) => req match {
          case BasicAuth(u, p) if verify(u, p, user) => {
        	userRepository.remove(name)
            NoContent
          }
          case _ => Fail(name)
        }
        case _ => NotFound
      }
    }
  }
}

class BookmarksPlan(override val userRepository: UserRepository)
extends UserRepositoryPlan(userRepository) {
  val logger = Logger(classOf[BookmarksPlan])

  def intent = {
    case req @ GET(Path(Seg("users" :: name :: "bookmarks" :: Nil))) => {
      logger.debug("GET /users/%s/bookmarks" format name)
      userRepository findByName name match {
    	case Some(user) => {
          val authorized = req match {
            case BasicAuth(u, p) if verify(u, p, user) => true
            case _ => false
          }
          // TODO representation based on content negotiation
          Ok ~> ResponseString(user.bookmarks filter ((e) => (authorized || ! e._2.restricted)) toString)
    	}
        case _ => NotFound
      }
    }
  }
}

class BookmarkPlan(override val userRepository: UserRepository)
extends UserRepositoryPlan(userRepository) {
  val logger = Logger(classOf[BookmarkPlan])

  def storeBookmarkFromForm(name: String, uri: String, form: Map[String, Seq[String]]) = {
	val user = userRepository findByName name get
    val bookmark = Bookmark(uri, 
    						new Date,
    		                form("bookmark[short_description]")(0), 
    		                form("bookmark[long_description]")(0), 
    		                form("bookmark[restrict]")(0) toBoolean, 
    		                user)
    user.bookmarks.put(uri, bookmark)
    bookmark
  }

  def intent = {
    case req @ GET(Path(Seg("users" :: name :: "bookmarks" :: uri))) => try {
      val uriString = uri mkString "/"
      logger.debug("GET /users/%s/bookmarks/%s" format(name, uriString))
      val Some(user) = userRepository findByName name
      val Some(bookmark) = user.bookmarks get uriString
      val true = bookmark.restricted || { val BasicAuth(u, p) = req ; verify(u, p, user) }
      Ok ~> ResponseString(bookmark toString)
    } catch { case _ => NotFound }

    case req @ PUT(Path(Seg("users" :: name :: "bookmarks" :: uri))) => try {
      val uriString = uri mkString "/"
      logger.debug("PUT /users/%s/bookmarks/%s" format(name, uriString))
      val Some(user) = userRepository findByName name
      req match {
        case BasicAuth(u, p) if verify(u, p, user) => {
          val Params(form) = req
          try { 
            user.bookmarks get uriString match {
        	  case Some(bookmark) => {
        	 	storeBookmarkFromForm(name, uriString, form)
        	 	NoContent
        	  }
              case _ => {
	        	val bookmark = storeBookmarkFromForm(name, uriString, form)
            	Created ~> ResponseString(bookmark toString)
              }
            }
          } catch { case _ => BadRequest }
        }
        case _ => Fail(name)
      }
    } catch { case _ => NotFound }

    case req @ DELETE(Path(Seg("users" :: name :: "bookmarks" :: uri))) => try {
      val uriString = uri mkString "/"
      logger.debug("DELETE /users/%s/bookmarks/%s" format(name, uriString))
      val Some(user) = userRepository findByName name
      val BasicAuth(u, p) = req
      val true = verify(u, p, user)
      val Some(_) = user.bookmarks remove uriString
      NoContent
    } catch { case _ => NotFound }
  }
}
