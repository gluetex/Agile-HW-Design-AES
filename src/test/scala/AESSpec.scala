import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Gen, Shrink}
import org.scalatestplus.scalacheck._
import AESReference._

//Integration test: the key goes into KeyExpansion, the round keys into AESEncrypt,
//so nothing from the software model is used to feed the hardware.
class AESSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {

  def pokeKey(dut: AES, key: Seq[Int]): Unit =
    dut.io.key.poke(bytesToBigInt(key).U(dut.keySize.W))

  //Send one block in, wait for the result and take it
  def encrypt(dut: AES, block: BigInt): BigInt = {
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.poke(block.U(128.W))
    dut.io.in.valid.poke(true.B)
    while (!dut.io.in.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
    var cycles = 1
    val nr = AESEncrypt.rounds(dut.keySize)
    while (!dut.io.out.valid.peek().litToBoolean) {
      dut.clock.step()
      cycles += 1
      assert(cycles <= nr + 1, "no ciphertext after Nr + 1 cycles")
    }
    val result = dut.io.out.bits.peek().litValue
    dut.clock.step()
    result
  }

  def hex(s: String): BigInt = BigInt(s, 16)

  def runNistFile(dut: AES, file: String): Unit =
    for (v <- NistVectors.load(file)) {
      pokeKey(dut, v.key)
      for ((pt, ct) <- v.plaintext.zip(v.ciphertext)) {
        assert(encrypt(dut, pt) == ct, s"$file COUNT = ${v.count}")
      }
    }

  val nistFiles = Seq("GFSbox", "KeySbox", "VarTxt", "VarKey", "MMT")

  val bytesGen: Int => Gen[Seq[Int]] = n => Gen.listOfN(n, Gen.choose(0, 255))
  implicit val noShrink: Shrink[Seq[Int]] = Shrink.shrinkAny

  //FIPS-197 Appendix B (128) and Appendix C (128, 192, 256)
  for ((keySize, key, pt, ct) <- Seq(
    (128, "2b7e151628aed2a6abf7158809cf4f3c", "3243f6a8885a308d313198a2e0370734", "3925841d02dc09fbdc118597196a0b32"),
    (128, "000102030405060708090a0b0c0d0e0f", "00112233445566778899aabbccddeeff", "69c4e0d86a7b0430d8cdb78070b4c55a"),
    (192, "000102030405060708090a0b0c0d0e0f1011121314151617", "00112233445566778899aabbccddeeff", "dda97ca4864cdfe06eaf70a0ec0d7191"),
    (256, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "00112233445566778899aabbccddeeff", "8ea2b7ca516745bfeafc49904b496089"))) {
    "AES" should s"encrypt the FIPS-197 example with a $keySize bit key $key" in {
      test(new AES(keySize)) { dut =>
        pokeKey(dut, hexToBytes(key))
        assert(encrypt(dut, hex(pt)) == hex(ct))
      }
    }
  }

  for (keySize <- Seq(128, 192, 256)) {
    behavior of s"AES-$keySize (KeyExpansion + AESEncrypt)"

    it should "match the JDK AES for random keys and plaintexts" in {
      test(new AES(keySize)) { dut =>
        forAll(bytesGen(keySize / 8), bytesGen(16), minSuccessful(100)) { (key, pt) =>
          pokeKey(dut, key)
          val ct = encrypt(dut, bytesToBigInt(pt))
          assert(bigIntToBytes(ct, 16) == encryptJdk(key, pt))
        }
      }
    }

    for (name <- nistFiles) {
      it should s"pass the NIST AESAVS ECB $name test vectors" in {
        test(new AES(keySize))(dut => runNistFile(dut, s"ECB$name$keySize.rsp"))
      }
    }
  }
}
