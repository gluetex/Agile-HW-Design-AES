import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Gen, Shrink}
import org.scalatestplus.scalacheck._

//MixColumns followed by InvMixColumns, the output should be the input again
class MixColumnsInvMixColumns extends Module {
  val io = IO(new Bundle {
    val in = Input(new State)
    val out = Output(new State)
  })
  val mixColumns = Module(new MixColumns)
  val invMixColumns = Module(new InvMixColumns)

  mixColumns.io.in := io.in
  invMixColumns.io.in := mixColumns.io.out
  io.out := invMixColumns.io.out
}

class InvMixColumnsSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {

  // software version of invmixcolumns to compare the hardware against
  def gmul(a: Int, b: Int): Int = {
    var x = a
    var y = b
    var p = 0
    while (y != 0) {
      if ((y & 1) != 0) p ^= x
      x = if ((x & 0x80) != 0) ((x << 1) ^ 0x11b) else (x << 1)
      y >>= 1
    }
    p
  }

  def invMixColumns(s: Seq[Seq[Int]]): Seq[Seq[Int]] = {
    val matrix = Seq(
      Seq(0x0e, 0x0b, 0x0d, 0x09),
      Seq(0x09, 0x0e, 0x0b, 0x0d),
      Seq(0x0d, 0x09, 0x0e, 0x0b),
      Seq(0x0b, 0x0d, 0x09, 0x0e))
    Seq.tabulate(4, 4) { (r, c) =>
      (0 to 3).map(k => gmul(matrix(r)(k), s(k)(c))).reduce(_ ^ _)
    }
  }

  def pokeState(in: State, s: Seq[Seq[Int]]): Unit =
    for (i <- 0 to 3; j <- 0 to 3) in.rows(i)(j).poke(s(i)(j).U)

  def expectState(out: State, s: Seq[Seq[Int]]): Unit =
    for (i <- 0 to 3; j <- 0 to 3) out.rows(i)(j).expect(s(i)(j).U)

  // the test vectors online are written as columns, so flip them into a state
  def fromColumns(cols: Seq[Seq[Int]]): Seq[Seq[Int]] =
    Seq.tabulate(4, 4) { (r, c) => cols(c)(r) }

  val stateGen: Gen[Seq[Seq[Int]]] =
    Gen.listOfN(4, Gen.listOfN(4, Gen.choose(0, 255)))
  // no shrinking, otherwise it makes states that arent 4x4
  implicit val noShrink: Shrink[Seq[Seq[Int]]] = Shrink.shrinkAny

  "InvMixColumns" should "undo MixColumns in the FIPS-197 Appendix B round 1 example" in {
    test(new InvMixColumns) { dut =>
      val afterMixColumns = Seq(
        Seq(0x04, 0xe0, 0x48, 0x28),
        Seq(0x66, 0xcb, 0xf8, 0x06),
        Seq(0x81, 0x19, 0xd3, 0x26),
        Seq(0xe5, 0x9a, 0x7a, 0x4c))
      val beforeMixColumns = Seq(
        Seq(0xd4, 0xe0, 0xb8, 0x1e),
        Seq(0xbf, 0xb4, 0x41, 0x27),
        Seq(0x5d, 0x52, 0x11, 0x98),
        Seq(0x30, 0xae, 0xf1, 0xe5))
      pokeState(dut.io.in, afterMixColumns)
      expectState(dut.io.out, beforeMixColumns)
    }
  }

  it should "undo the common single column test vectors" in {
    test(new InvMixColumns) { dut =>
      pokeState(dut.io.in, fromColumns(Seq(
        Seq(0x8e, 0x4d, 0xa1, 0xbc),
        Seq(0x9f, 0xdc, 0x58, 0x9d),
        Seq(0xc6, 0xc6, 0xc6, 0xc6),
        Seq(0xd5, 0xd5, 0xd7, 0xd6))))
      expectState(dut.io.out, fromColumns(Seq(
        Seq(0xdb, 0x13, 0x53, 0x45),
        Seq(0xf2, 0x0a, 0x22, 0x5c),
        Seq(0xc6, 0xc6, 0xc6, 0xc6),
        Seq(0xd4, 0xd4, 0xd4, 0xd5))))

      pokeState(dut.io.in, fromColumns(Seq(
        Seq(0x01, 0x01, 0x01, 0x01),
        Seq(0x4d, 0x7e, 0xbd, 0xf8),
        Seq(0x00, 0x00, 0x00, 0x00),
        Seq(0xff, 0xff, 0xff, 0xff))))
      expectState(dut.io.out, fromColumns(Seq(
        Seq(0x01, 0x01, 0x01, 0x01),
        Seq(0x2d, 0x26, 0x31, 0x4c),
        Seq(0x00, 0x00, 0x00, 0x00),
        Seq(0xff, 0xff, 0xff, 0xff))))
    }
  }

  it should "match the reference model for random states" in {
    test(new InvMixColumns) { dut =>
      forAll(stateGen, minSuccessful(200)) { s =>
        pokeState(dut.io.in, s)
        expectState(dut.io.out, invMixColumns(s))
      }
    }
  }

  it should "be the inverse of MixColumns for random states" in {
    test(new MixColumnsInvMixColumns) { dut =>
      forAll(stateGen, minSuccessful(200)) { s =>
        pokeState(dut.io.in, s)
        expectState(dut.io.out, s)
      }
    }
  }
}
