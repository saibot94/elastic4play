package org.elastic4play.controllers

import java.nio.file.Path
import java.util.Locale

import scala.Right
import scala.collection.GenTraversableOnce
import scala.util.Try

import play.api.Logger
import play.api.libs.json.{ JsArray, JsBoolean, JsNull, JsNumber, JsObject, JsString, JsValue, Json }
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.libs.streams.Accumulator
import play.api.mvc.{ BodyParser, MultipartFormData, RequestHeader }

import org.elastic4play.BadRequestError

import JsonFormat.{ fieldsReader, pathFormat }

/**
 * Define a data value from HTTP request. It can be simple string, json, file or null (maybe xml in future)
 */
sealed trait InputValue {
  def jsonValue: JsValue
}

/**
 * Define a data value from HTTP request as simple string
 */
case class StringInputValue(data: Seq[String]) extends InputValue {
  def jsonValue: JsValue = Json.toJson(data)
}
object StringInputValue {
  def apply(s: String): StringInputValue = this(Seq(s))
}

/**
 * Define a data value from HTTP request as json value
 */
case class JsonInputValue(data: JsValue) extends InputValue {
  def jsonValue: JsValue = data
}

/**
 * Define a data value from HTTP request as file (filename, path to temporary file and content type). Other data are lost
 */
case class FileInputValue(name: String, filepath: Path, contentType: String) extends InputValue {
  def jsonValue: JsObject = Json.obj("name" -> name, "filepath" -> filepath, "contentType" -> contentType)
}

/**
 * Define a data value from HTTP request as null (empty value)
 */
object NullInputValue extends InputValue {
  def jsonValue: JsValue = JsNull
}

/**
 * Contain data values from HTTP request
 */
class Fields(private val fields: Map[String, InputValue]) {
  /**
   * Get data value as String. Returns None if field doesn't exist or format is not a string
   */
  def getString(name: String): Option[String] = {
    fields.get(name) collect {
      case StringInputValue(Seq(s))    => s
      case JsonInputValue(JsString(s)) => s
    }
  }

  /**
   * Get data value as list of String. Returns None if field doesn't exist or format is not a list of string
   */
  def getStrings(name: String): Option[Seq[String]] = fields.get(name) flatMap {
    case StringInputValue(ss) => Some(ss)
    case JsonInputValue(JsArray(js)) => js.foldLeft[Option[Seq[String]]](Some(Nil)) {
      case (Some(l), JsString(s)) => Some(s +: l)
      case _                      => None
    }
    case _ => None
  }

  /**
   * Get data value as list of String. Returns None if field doesn't exist or format is not a list of string
   */
  def getStrings(name: String, separator: String): Option[Seq[String]] = fields.get(name) flatMap {
    case StringInputValue(ss) => Some(ss.flatMap(_.split(separator)).filterNot(_.isEmpty))
    case JsonInputValue(JsArray(js)) => js.foldLeft[Option[Seq[String]]](Some(Nil)) {
      case (Some(l), JsString(s)) => Some(s +: l)
      case _                      => None
    }
    case _ => None
  }

  /**
   * Get data value as Long. Returns None if field doesn't exist or format is not a Long
   */
  def getLong(name: String): Option[Long] = fields.get(name) flatMap {
    case StringInputValue(Seq(s))    => Try(s.toLong).toOption
    case JsonInputValue(JsNumber(b)) => Some(b.longValue)
    case _                           => None
  }

  def getBoolean(name: String): Option[Boolean] = fields.get(name) flatMap {
    case JsonInputValue(JsBoolean(b)) => Some(b)
    case StringInputValue(Seq(s))     => Try(s.toBoolean).orElse(Try(s.toLong == 1)).toOption
    case _                            => None
  }
  /**
   * Get data value as json. Returns None if field doesn't exist or can't be converted to json
   */
  def getValue(name: String): Option[JsValue] = fields.get(name) collect {
    case JsonInputValue(js)       => js
    case StringInputValue(Seq(s)) => JsString(s)
    case StringInputValue(ss)     => Json.toJson(ss)
  }

  def getValues(name: String): Seq[JsValue] = fields.get(name).toSeq flatMap {
    case JsonInputValue(JsArray(js)) => js
    case StringInputValue(ss)        => ss.map(s => JsString(s))
    case _                           => Nil
  }
  /**
   * Extract all fields, name and value
   */
  def map[A](f: ((String, InputValue)) => A) = fields.map(f)

  /**
   * Extract all field values
   */
  def mapValues(f: (InputValue) => InputValue) = new Fields(fields.mapValues(f))

  /**
   * Returns a copy of this class with a new field (or replacing existing field)
   */
  def set(name: String, value: InputValue): Fields = new Fields(fields + (name -> value))

  /**
   * Returns a copy of this class with a new field (or replacing existing field)
   */
  def set(name: String, value: String): Fields = set(name, StringInputValue(Seq(value)))

  /**
   * Returns a copy of this class with a new field (or replacing existing field)
   */
  def set(name: String, value: JsValue): Fields = set(name, JsonInputValue(value))

  /**
   * Returns a copy of this class with a new field if value is not None otherwise returns this
   */
  def set(name: String, value: Option[JsValue]): Fields = value.fold(this)(v => set(name, v))

  /**
   * Return a copy of this class without the specified field
   */
  def unset(name: String): Fields = new Fields(fields - name)

  /**
   * Returns true if the specified field name is present
   */
  def contains(name: String) = fields.contains(name)

  def isEmpty = fields.isEmpty

  def addIfAbsent(name: String, value: String) = getString(name) match {
    case Some(_) => this
    case None    => set(name, value)
  }

  def ++(other: GenTraversableOnce[(String, InputValue)]) = new Fields(fields ++ other)

  override def toString = fields.toString()
}

object Fields {
  val empty: Fields = new Fields(Map.empty[String, InputValue])

  /**
   * Create an instance of Fields from a JSON object
   */
  def apply(obj: JsObject): Fields = {
    val fields = obj.value.mapValues(v => JsonInputValue(v))
    new Fields(fields.toMap)
  }

  def apply(fields: Map[String, InputValue]) = {
    if (fields.keysIterator.find(_.startsWith("_")).isDefined)
      throw BadRequestError("Field starting with '_' is forbidden")
    new Fields(fields)
  }
}

class FieldsBodyParser extends BodyParser[Fields] {
  import play.api.libs.iteratee.Execution.Implicits.trampoline
  import play.api.mvc.BodyParsers.parse._

  def apply(request: RequestHeader) = {
      def queryFields = request.queryString.mapValues(v => StringInputValue(v))

    request.contentType.map(_.toLowerCase(Locale.ENGLISH)) match {

      case Some("text/json") | Some("application/json") => json[Fields].map(f => f ++ queryFields).apply(request)

      case Some("application/x-www-form-urlencoded") => tolerantFormUrlEncoded
        .map { form => Fields(form.mapValues(v => StringInputValue(v))) }
        .map(f => f ++ queryFields)
        .apply(request)

      case Some("multipart/form-data") => multipartFormData.map {
        case MultipartFormData(dataParts, files, badParts) =>
          val dataFields = dataParts
            .getOrElse("_json", Nil)
            .headOption
            .map { s =>
              Json.parse(s).as[JsObject]
                .value.toMap
                .mapValues(v => JsonInputValue(v))
            }
            .getOrElse(Map.empty)
          val fileFields = files.map { f => f.key -> FileInputValue(f.filename.split("[/\\\\]").last, f.ref.file.toPath, f.contentType.getOrElse("application/octet-stream")) }
          Fields(dataFields ++ fileFields ++ queryFields)
      }.apply(request)

      case contentType =>
        val contentLength = request.headers.get("Content-Length").fold(0)(_.toInt)
        if (contentLength != 0)
          Logger.warn(s"Unrecognized content-type : ${contentType.getOrElse("not set")} on $request (length=$contentLength)")
        Accumulator.done(Right(Fields.empty))
    }
  }
}