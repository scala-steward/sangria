package sangria.schema

import language.higherKinds

import org.scalatest.{Matchers, WordSpec}
import sangria.execution.Executor
import sangria.integration.ToInput
import sangria.macros._
import sangria.util.AwaitSupport
import sangria.integration.ScalaInput.scalaInput

import scala.concurrent.ExecutionContext.Implicits.global

class DefaultValuesSpec extends WordSpec with Matchers with AwaitSupport {
  def check[Default](inputType: InputType[_], defaultValue: Default, expectedResult: Any, expectedDefault: String)(implicit ev: ToInput[Default, _]) = {
    import sangria.integration.sprayJson._
    import spray.json._

    class CaptureCtx(var arg: Option[Any] = None)

    val arg = Argument("test", OptionInputType(inputType), defaultValue = defaultValue)

    val QueryType = ObjectType("Query", fields[CaptureCtx, Unit](
      Field("foo", StringType,
        arguments = arg :: Nil,
        resolve = ctx ⇒ {
          ctx.ctx.arg = Some(ctx.arg[Any]("test"))
          "result"
        })
    ))

    val schema = Schema(QueryType)

    val query = graphql"{foo}"

    val ctx = new CaptureCtx

    Executor.execute(schema, query, userContext = ctx).await should be (JsObject("data" → JsObject("foo" → JsString("result"))))

    ctx.arg should be (Some(expectedResult))

    val introspectionQuery =
      graphql"""
        {
          __schema {
            queryType {
              fields {
                args {
                  defaultValue
                }
              }
            }
          }
        }
      """

    Executor.execute(schema, introspectionQuery, userContext = ctx).await should be (
      JsObject("data" →
        JsObject("__schema" →
          JsObject("queryType" →
            JsObject("fields" → JsArray(
              JsObject("args" → JsArray(
                JsObject("defaultValue" → JsString(expectedDefault))))))))))

  }

  def complexInputType[S, C](sharesDefault: S, commentsDefault: C)(implicit sev: ToInput[S, _], cev: ToInput[C, _]) = {
    val SharesType = InputObjectType("Shares", fields = List(
      InputField("twitter", OptionInputType(LongType), defaultValue = 123),
      InputField("facebook", OptionInputType(LongType), defaultValue = 1)
    ))

    val CommentType = InputObjectType("Comment", fields = List(
      InputField("author", OptionInputType(StringType), defaultValue = "anonymous"),
      InputField("text", StringType),
      InputField("likes", OptionInputType(BigDecimalType), defaultValue = BigDecimal("1.5"))
    ))

    val BlogType = InputObjectType("Blog", fields = List(
      InputField("title", StringType),
      InputField("text", OptionInputType(StringType), defaultValue = "Hello World!"),
      InputField("views", OptionInputType(IntType), defaultValue = 12),
      InputField("tags", OptionInputType(ListInputType(StringType)), defaultValue = scalaInput(List("beginner", "scala"))),
      InputField("shares", OptionInputType(SharesType), defaultValue = sharesDefault),
      InputField("comments", OptionInputType(ListInputType(CommentType)), defaultValue = commentsDefault)
    ))

    BlogType
  }

  case class Shares(twitter: Int, facebook: Int)
  case class Comment(author: String, text: String, likes: BigDecimal)

  "Default values" when {
    "used with NotNull input types" should {
      "not allow default values for NotNull arguments" in {
        an [IllegalArgumentException] should be thrownBy Argument("boom", IntType, defaultValue = 1)
      }

      "not allow default values for NotNull input fields" in {
        an [IllegalArgumentException] should be thrownBy InputField("boom", IntType, defaultValue = 1)
      }
    }

    "used with scala map-like data structures" should {
      "default Int" in check(IntType,
        defaultValue = 1,
        expectedResult = 1,
        expectedDefault = "1")

      "default Long" in check(LongType,
        defaultValue = 13545436553654L,
        expectedResult = 13545436553654L,
        expectedDefault = "13545436553654")

      "default BigDecimal" in check(BigDecimalType,
        defaultValue = BigDecimal("47656823564532764576325476352742.764576437"),
        expectedResult = BigDecimal("47656823564532764576325476352742.764576437"),
        expectedDefault = "47656823564532764576325476352742.764576437")

      "default BigInt" in check(BigIntType,
        defaultValue = BigInt("47656823564532764576325476352742"),
        expectedResult = BigInt("47656823564532764576325476352742"),
        expectedDefault = "47656823564532764576325476352742")

      "default Float" in check(FloatType,
        defaultValue = 234.05D,
        expectedResult = 234.05D,
        expectedDefault = "234.05")

      "default String" in check(StringType,
        defaultValue = "Hello",
        expectedResult = "Hello",
        expectedDefault = "\"Hello\"")

      "default Boolean" in check(BooleanType,
        defaultValue = true,
        expectedResult = true,
        expectedDefault = "true")

      "default scala list of Int" in check(ListInputType(IntType),
        defaultValue = scalaInput(List(1, 2, 4)),
        expectedResult = List(1, 2, 4),
        expectedDefault = "[1,2,4]")

      "default scala list of String" in check(ListInputType(StringType),
        defaultValue = scalaInput(Vector("Hello", "World")),
        expectedResult = List("Hello", "World"),
        expectedDefault = "[\"Hello\",\"World\"]")

      val ScalaInputType = complexInputType(
        sharesDefault = scalaInput(Map("twitter" → 78)),
        commentsDefault = scalaInput(List(Map("text" → "Foo"), Map("text" → "bar", "likes" → 3.2D))))

      "default scala complex object" in check(
        ScalaInputType,
          defaultValue = scalaInput(Map("title" → "Post #1", "text" → "Amazing!", "comments" → List(Map("text" → "First! :P")))),
          expectedResult = Map(
            "title" → "Post #1",
            "text" → "Amazing!",
            "tags" → List("beginner", "scala"),
            "views" → 12,
            "shares" → Map("twitter" → 78, "facebook" → 1),
            "comments" → List(Map("author" → "anonymous", "text" → "First! :P", "likes" → 1.5))),
          expectedDefault = "{\"tags\":[\"beginner\",\"scala\"],\"text\":\"Amazing!\",\"shares\":{\"twitter\":78,\"facebook\":1},\"views\":12,\"title\":\"Post #1\",\"comments\":[{\"author\":\"anonymous\",\"text\":\"First! :P\",\"likes\":1.5}]}")

      "validate scalar default values" in {
        a [SchemaValidationException] should be thrownBy check(
          IntType,
          defaultValue = "Bananas!",
          expectedResult = (),
          expectedDefault = "")
      }

      "validate complex default values" in {
        val BrokenInputType = complexInputType(
          sharesDefault = scalaInput(Map("facebook" → 78)),
          commentsDefault = scalaInput(List(Map("text" → "Foo"), Map("likes" → 3.2D))))

        a [SchemaValidationException] should be thrownBy check(
          BrokenInputType,
          defaultValue = scalaInput(Map("text" → "Amazing!", "comments" → List(Map("text" → "First! :P")))),
          expectedResult = (),
          expectedDefault = "")
      }
    }

    "used with spray JSON values" should {
      import spray.json._
      import sangria.integration.sprayJson.sprayJsonToInput

      "default Int" in check(IntType,
        defaultValue = JsNumber(1),
        expectedResult = 1,
        expectedDefault = "1")

      "default Long" in check(LongType,
        defaultValue = JsNumber(17465784658743L),
        expectedResult = 17465784658743L,
        expectedDefault = "17465784658743")

      "default BigDecimal" in check(BigDecimalType,
        defaultValue = JsNumber(BigDecimal("1746578465874346587465843.48564736578436")),
        expectedResult = BigDecimal("1746578465874346587465843.48564736578436"),
        expectedDefault = "1746578465874346587465843.48564736578436")

      "default String" in check(StringType,
        defaultValue = JsString("Bananas!"),
        expectedResult = "Bananas!",
        expectedDefault = "\"Bananas!\"")

      "default scala list of Int" in check(ListInputType(IntType),
        defaultValue = JsArray(JsNumber(1), JsNumber(23), JsNumber(56)),
        expectedResult = List(1, 23, 56),
        expectedDefault = "[1,23,56]")

      "default scala list of String" in check(ListInputType(StringType),
        defaultValue = JsArray(JsString("foo"), JsString("bar")),
        expectedResult = List("foo", "bar"),
        expectedDefault = "[\"foo\",\"bar\"]")

      val JsonInputType = complexInputType(
        sharesDefault = JsObject("twitter" → JsNumber(78)),
        commentsDefault = """[{"text": "Foo"}, {"text": "bar", "likes": 3.2}]""".parseJson)

      "default scala complex object" in check(
        JsonInputType,
        defaultValue = """{"title": "Post #1", "text": "Amazing!", "comments": [{"text": "First! :P"}]}""".parseJson,
        expectedResult = Map(
          "title" → "Post #1",
          "text" → "Amazing!",
          "tags" → List("beginner", "scala"),
          "views" → 12,
          "shares" → Map("twitter" → 78, "facebook" → 1),
          "comments" → List(Map("author" → "anonymous", "text" → "First! :P", "likes" → 1.5))),
        expectedDefault = "{\"tags\":[\"beginner\",\"scala\"],\"text\":\"Amazing!\",\"shares\":{\"twitter\":78,\"facebook\":1},\"views\":12,\"title\":\"Post #1\",\"comments\":[{\"author\":\"anonymous\",\"text\":\"First! :P\",\"likes\":1.5}]}")

      "manual typeclass-based serialisation" in {
        implicit object SharesToInput extends ToInput[Shares, JsValue] {
          override def toInput(value: Shares) = {
            val json = JsObject("twitter" → JsNumber(value.twitter), "facebook" → JsNumber(value.facebook))

            json → sangria.integration.sprayJson.SprayJsonInputUnmarshaller
          }
        }

        implicit object CommentToInput extends ToInput[Comment, JsValue] {
          override def toInput(value: Comment) = {
            val json = JsObject(
              "author" → JsString(value.author),
              "text" → JsString(value.text),
              "likes" → JsNumber(value.likes))

            json → sangria.integration.sprayJson.SprayJsonInputUnmarshaller
          }
        }

        implicit def listToInput[T](implicit ev: ToInput[T, JsValue]): ToInput[List[T], JsValue] =
          new ToInput[List[T], JsValue] {
            override def toInput(value: List[T]) = {
              val json = JsArray(value.toVector map ((v: T) ⇒ ev.toInput(v)._1))

              json → sangria.integration.sprayJson.SprayJsonInputUnmarshaller
            }
          }

        val CustomInputType = complexInputType(
          sharesDefault = Shares(123, 456),
          commentsDefault = List(Comment("John Doe", "Nice post!", BigDecimal(100)), Comment("Foo", "Bar", BigDecimal(0.1))))

        check(
          CustomInputType,
          defaultValue = """{"title": "Post #1", "text": "Amazing!"}""".parseJson,
          expectedResult = Map(
            "title" → "Post #1",
            "text" → "Amazing!",
            "tags" → List("beginner", "scala"),
            "views" → 12,
            "shares" → Map("twitter" → 123, "facebook" → 456),
            "comments" → List(
              Map("author" → "John Doe", "text" → "Nice post!", "likes" → 100),
              Map("author" → "Foo", "text" → "Bar", "likes" → 0.1))),
          expectedDefault = "{\"tags\":[\"beginner\",\"scala\"],\"text\":\"Amazing!\",\"shares\":{\"twitter\":123,\"facebook\":456},\"views\":12,\"title\":\"Post #1\",\"comments\":[{\"author\":\"John Doe\",\"text\":\"Nice post!\",\"likes\":100},{\"author\":\"Foo\",\"text\":\"Bar\",\"likes\":0.1}]}")
      }

      "generated typeclass-based serialisation" in {
        object MyJsonProtocol extends DefaultJsonProtocol {
          implicit val sharesFormat = jsonFormat2(Shares.apply)
          implicit val commentFormat = jsonFormat3(Comment.apply)
        }

        import MyJsonProtocol._
        import sangria.integration.sprayJson.sprayJsonWriterToInput

        val CustomInputType = complexInputType(
          sharesDefault = Shares(123, 456),
          commentsDefault = List(Comment("John Doe", "Nice post!", BigDecimal(100)), Comment("Foo", "Bar", BigDecimal(0.1))))

        check(
          CustomInputType,
          defaultValue = """{"title": "Post #1", "text": "Amazing!"}""".parseJson,
          expectedResult = Map(
            "title" → "Post #1",
            "text" → "Amazing!",
            "tags" → List("beginner", "scala"),
            "views" → 12,
            "shares" → Map("twitter" → 123, "facebook" → 456),
            "comments" → List(
              Map("author" → "John Doe", "text" → "Nice post!", "likes" → 100),
              Map("author" → "Foo", "text" → "Bar", "likes" → 0.1))),
          expectedDefault = "{\"tags\":[\"beginner\",\"scala\"],\"text\":\"Amazing!\",\"shares\":{\"twitter\":123,\"facebook\":456},\"views\":12,\"title\":\"Post #1\",\"comments\":[{\"author\":\"John Doe\",\"text\":\"Nice post!\",\"likes\":100},{\"author\":\"Foo\",\"text\":\"Bar\",\"likes\":0.1}]}")
      }
    }

    // FIXME: commented out because play requires Java 8 -→ Move to integration library in order to remain compatible with Java 7 here
//    "used with play JSON values" should {
//      "generated typeclass-based serialisation" in {
//        import play.api.libs.json._
//
//        implicit val sharesWrites = Json.writes[Shares]
//        implicit val commentWrites = Json.writes[Comment]
//
//        import sangria.integration.playJson._
//
//        val CustomInputType = complexInputType(
//          sharesDefault = Shares(123, 456),
//          commentsDefault = List(Comment("John Doe", "Nice post!", BigDecimal(100)), Comment("Foo", "Bar", BigDecimal(0.1))))
//
//        check(
//          CustomInputType,
//          defaultValue = Json.parse("""{"title": "Post #1", "text": "Amazing!"}"""),
//          expectedResult = Map(
//            "title" → "Post #1",
//            "text" → "Amazing!",
//            "tags" → List("beginner", "scala"),
//            "views" → 12,
//            "shares" → Map("twitter" → 123, "facebook" → 456),
//            "comments" → List(
//              Map("author" → "John Doe", "text" → "Nice post!", "likes" → 100),
//              Map("author" → "Foo", "text" → "Bar", "likes" → 0.1))),
//          expectedDefault = "{\"tags\":[\"beginner\",\"scala\"],\"text\":\"Amazing!\",\"shares\":{\"twitter\":123,\"facebook\":456},\"views\":12,\"title\":\"Post #1\",\"comments\":[{\"author\":\"John Doe\",\"text\":\"Nice post!\",\"likes\":100},{\"author\":\"Foo\",\"text\":\"Bar\",\"likes\":0.1}]}")
//      }
//    }
  }
}