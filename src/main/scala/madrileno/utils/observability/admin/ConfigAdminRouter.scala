package madrileno.utils.observability.admin

import cats.effect.IO
import com.typesafe.config.{Config, ConfigFactory, ConfigList, ConfigObject, ConfigResolveOptions, ConfigValue, ConfigValueType}
import io.circe.Json
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.util.Locale
import scala.jdk.CollectionConverters.*

class ConfigAdminRouter(redactedPaths: Set[String]) extends BaseRouter {

  private val appConfig: Config =
    ConfigFactory.parseResources("application.conf").resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(true))

  val routes: Route =
    (get & pathPrefix("config") & pathEndOrSingleSlash) {
      complete(IO.delay(ConfigAdminRouter.redact(appConfig, redactedPaths)).map[ToResponseMarshallable](Ok -> _))
    }
}

object ConfigAdminRouter {
  private val Redacted                     = Json.fromString("[REDACTED]")
  private val DefaultKeywords: Set[String] = Set("password", "secret", "credential", "access-key", "token")

  def redact(config: Config, redactedPaths: Set[String]): Json =
    walk(config.root(), "", redactedPaths)

  private def shouldRedact(
    key: String,
    path: String,
    redactedPaths: Set[String]
  ): Boolean = {
    val lower = key.toLowerCase(Locale.ROOT)
    DefaultKeywords.exists(lower.contains) || redactedPaths.contains(path)
  }

  private def walk(
    value: ConfigValue,
    path: String,
    redactedPaths: Set[String]
  ): Json = value.valueType match {
    case ConfigValueType.OBJECT =>
      val obj = value match {
        case o: ConfigObject => o
        case other           => throw new IllegalStateException(s"expected ConfigObject for OBJECT, got ${other.getClass.getName}")
      }
      val entries = obj.asScala.toList.sortBy(_._1).map { case (k, v) =>
        val childPath = if (path.isEmpty) k else s"$path.$k"
        val isPrimitive = v.valueType match {
          case ConfigValueType.OBJECT | ConfigValueType.LIST => false
          case _                                             => true
        }
        val rendered =
          if (isPrimitive && shouldRedact(k, childPath, redactedPaths)) Redacted
          else walk(v, childPath, redactedPaths)
        k -> rendered
      }
      Json.obj(entries*)
    case ConfigValueType.LIST =>
      val list = value match {
        case l: ConfigList => l
        case other         => throw new IllegalStateException(s"expected ConfigList for LIST, got ${other.getClass.getName}")
      }
      Json.fromValues(list.asScala.toList.zipWithIndex.map { case (v, i) => walk(v, s"$path[$i]", redactedPaths) })
    case ConfigValueType.STRING => Json.fromString(String.valueOf(value.unwrapped()))
    case ConfigValueType.BOOLEAN =>
      value.unwrapped() match {
        case b: java.lang.Boolean => Json.fromBoolean(b)
        case other                => Json.fromString(String.valueOf(other))
      }
    case ConfigValueType.NUMBER =>
      value.unwrapped() match {
        case n: java.lang.Long    => Json.fromLong(n)
        case n: java.lang.Integer => Json.fromInt(n)
        case n: java.lang.Double  => Json.fromDoubleOrString(n)
        case other                => Json.fromString(String.valueOf(other))
      }
    case ConfigValueType.NULL => Json.Null
  }
}
