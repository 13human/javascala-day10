package academy.javascala

import akka.actor.typed.scaladsl.AskPattern.{Askable, _}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, DispatcherSelector}
import akka.util.Timeout

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

object _1ImageConverter {

  @throws[Exception]
  def splitImage(image: BufferedImage, rows: Int, cols: Int): Array[BufferedImage] = {
    val chunks = rows * cols
    val chunkWidth = image.getWidth / cols // determines the chunk width and height
    val chunkHeight = image.getHeight / rows
    var count = 0
    val imgs = new Array[BufferedImage](chunks) //Image array to hold image chunks
    for (x <- 0 until rows) {
      for (y <- 0 until cols) { //Initialize the image array with image chunks
        var imageType = image.getType
        if (imageType == 0) imageType = 5
        imgs(count) = new BufferedImage(chunkWidth, chunkHeight, imageType)
        // draws the image chunk
        val gr = imgs({
          count += 1;
          count - 1
        }).createGraphics
        gr.drawImage(image, 0, 0, chunkWidth, chunkHeight, chunkWidth * y, chunkHeight * x, chunkWidth * y + chunkWidth, chunkHeight * x + chunkHeight, null)
        gr.dispose()
      }
    }

    imgs
  }
  def joinBufferedImage(img1: BufferedImage, img2: BufferedImage, horizontal: Boolean): BufferedImage = { //do some calculate first
    val offset = 0
    val (wid, height) = if (horizontal) {
      val wid = img1.getWidth + img2.getWidth + offset
      val height = Math.max(img1.getHeight, img2.getHeight) + offset
      (wid, height)
    } else {
      val wid = Math.max(img1.getWidth, img2.getWidth) + offset
      val height = img1.getHeight + img2.getHeight + offset
      (wid, height)
    }

    //create a new buffer and draw two image into the new image
    val newImage = new BufferedImage(wid, height, BufferedImage.TYPE_INT_ARGB)
    val g2 = newImage.createGraphics
    val oldColor = g2.getColor
    //fill background
    g2.setPaint(Color.WHITE)
    g2.fillRect(0, 0, wid, height)
    //draw image
    g2.setColor(oldColor)
    if (horizontal) {
      g2.drawImage(img1, null, 0, 0)
      g2.drawImage(img2, null, img1.getWidth + offset, 0)
    } else {
      g2.drawImage(img1, null, 0, 0)
      g2.drawImage(img2, null, 0, img1.getHeight + offset)
    }

    g2.dispose()
    newImage
  }

  def pixels2Gray(R: Int, G: Int, B: Int): Int = (R + G + B) / 3

  def makeGray(testImage: java.awt.image.BufferedImage): BufferedImage = {
    val w = testImage.getWidth
    val h = testImage.getHeight
    for {
      w1 <- (0 until w).toVector
      h1 <- (0 until h).toVector
    } yield {
      val col = testImage.getRGB(w1, h1)
      val R = (col & 0xff0000) / 65536
      val G = (col & 0xff00) / 256
      val B = (col & 0xff)
      val graycol = pixels2Gray(R, G, B)
      testImage.setRGB(w1, h1, new Color(graycol, graycol, graycol).getRGB)
    }
    //Thread.sleep(20)
    testImage
  }

  def main(args: Array[String]): Unit = {
    val testImage = ImageIO.read(new File("src/main/resources/images/evil-toddler.png"))

    val t1 = System.currentTimeMillis()
    val splittedImage = splitImage(testImage, 10, 10).map(makeGray)
      .sliding(10, 10)
      .map(_.reduce(joinBufferedImage(_, _, true)))
      .reduce(joinBufferedImage(_, _, false))
    ImageIO.write(splittedImage, "png", new File("src/main/resources/processed/par.png"))


    val t2 = System.currentTimeMillis()

    val grayImage = makeGray(testImage)
    ImageIO.write(grayImage, "png", new File("src/main/resources/processed/single.png"))

    val t3 = System.currentTimeMillis()

    implicit val as: ActorSystem[ImageProcessor.MakeImageGray] = ActorSystem(Behaviors.empty, s"root")

    val workers = (1 to 100).map{ idx =>
      (idx, as.systemActorOf(ImageProcessor.apply(), s"worker-$idx"))
    }
   /* val splittedImage2 = Future.sequence {
      splitImage(testImage, 10, 10).toList
        .zipWithIndex.map { case (image, i) =>
        //val t = workers.find(_._1 == i).get._2.ask()
        as.ask((ref: ActorRef[ImageProcessor.GrayImage]) =>
          ImageProcessor.MakeImageGray(image = image, idx = i, replyTo = ref))
      }
    }
      .map(_.sortBy(_.idx).map(_.msg))
      .map {
        _
          .sliding(10, 10)
          .map(_.reduce(joinBufferedImage(_, _, true)))
          .reduce(joinBufferedImage(_, _, false))
      }.map { img =>
      ImageIO.write(img, "png", new File("src/main/resources/processed/actors.png"))
      System.currentTimeMillis()
    }
   */
    implicit val timeout: Timeout = 3.seconds
    implicit val ec = as.dispatchers.lookup(DispatcherSelector.fromConfig("your-dispatcher"))
    val t4 = System.currentTimeMillis()
    val splittedImage2 = Future.sequence {
      splitImage(testImage, 10, 10).toList
        .zipWithIndex.map { case (image, i) =>
        as.ask((ref: ActorRef[ImageProcessor.GrayImage]) =>
          ImageProcessor.MakeImageGray(image = image, idx = i, replyTo = ref))
      }
    }
      .map(_.sortBy(_.idx).map(_.msg))
      .map {
        _
          .sliding(10, 10)
          .map(_.reduce(joinBufferedImage(_, _, true)))
          .reduce(joinBufferedImage(_, _, false))
      }.map { img =>
      ImageIO.write(img, "png", new File("src/main/resources/processed/actors.png"))
      System.currentTimeMillis()
    }

    val t5 = Await.result(splittedImage2, 1.minute)

    println(t2 - t1, t3 - t2, t5 - t4)
  }
}

object ActorSystemGuardian {

  def apply(): Behavior[ImageProcessor.MakeImageGray] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage { message =>
        val processor = context.spawn(ImageProcessor.apply(), s"processor-${message.idx}")
        processor ! message
        Behaviors.same
      }
    }
}

object ImageProcessor {
  final case class MakeImageGray(image: BufferedImage, idx: Int, replyTo: ActorRef[GrayImage])

  final case class GrayImage(msg: BufferedImage, idx: Int)

  def apply(): Behavior[MakeImageGray] = Behaviors.receive { (context, message) =>
    Future {
      _1ImageConverter.makeGray(message.image)
    }(context.executionContext)
      .foreach { gray =>
        message.replyTo ! GrayImage(gray, message.idx)
      }(context.executionContext)

    Behaviors.stopped
  }
}

