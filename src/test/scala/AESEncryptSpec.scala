import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Gen, Shrink}
import org.scalatestplus.scalacheck._
import AESReference._

class AESEncryptSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {

  //The full Monte Carlo test is 100 x 1000 encryptions and takes a while in simulation,
  //so by default only the first few records are run. Set AES_FULL_MCT=1 to run all of them.
  val mctRecords: Int = if (sys.env.contains("AES_FULL_MCT")) Int.MaxValue else 5

  //Until the KeyExpansion module is done, the round keys come from the software model
  def pokeKey(dut: AESEncrypt, key: Seq[Int]): Unit = {
    val roundKeys = expandKey(key)
    for (k <- 0 to dut.nr; i <- 0 to 3; j <- 0 to 3)
      dut.io.roundKeys(k).rows(i)(j).poke(roundKeys(k)(i)(j).U)
  }

  //Send one block in, wait for the result and take it
  def encrypt(dut: AESEncrypt, block: BigInt): BigInt = {
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.poke(block.U(128.W))
    dut.io.in.valid.poke(true.B)
    while (!dut.io.in.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
    var cycles = 1
    while (!dut.io.out.valid.peek().litToBoolean) {
      dut.clock.step()
      cycles += 1
      assert(cycles <= dut.nr + 1, "no ciphertext after Nr + 1 cycles")
    }
    val result = dut.io.out.bits.peek().litValue
    dut.clock.step()
    result
  }

  def hex(s: String): BigInt = BigInt(s, 16)

  def runNistFile(dut: AESEncrypt, file: String): Unit =
    for (v <- NistVectors.load(file)) {
      pokeKey(dut, v.key)
      //ECB: every block is encrypted on its own with the same key
      for ((pt, ct) <- v.plaintext.zip(v.ciphertext)) {
        assert(encrypt(dut, pt) == ct, s"$file COUNT = ${v.count}")
      }
    }

  val nistFiles = Seq("GFSbox", "KeySbox", "VarTxt", "VarKey", "MMT")

  val bytesGen: Int => Gen[Seq[Int]] = n => Gen.listOfN(n, Gen.choose(0, 255))
  implicit val noShrink: Shrink[Seq[Int]] = Shrink.shrinkAny

  "The software KeyExpansion model" should "give the last AES-128 round key of FIPS-197 Appendix A.1" in {
    val roundKeys = expandKey(hexToBytes("2b7e151628aed2a6abf7158809cf4f3c"))
    val words = Seq("d014f9a8", "c9ee2589", "e13f0cc8", "b6630ca6").map(hexToBytes)
    assert(roundKeys(10) == Seq.tabulate(4, 4)((r, c) => words(c)(r)))
  }

  behavior of "AESEncrypt (AES-128)"

  it should "encrypt the FIPS-197 Appendix B example" in {
    test(new AESEncrypt(128)) { dut =>
      pokeKey(dut, hexToBytes("2b7e151628aed2a6abf7158809cf4f3c"))
      assert(encrypt(dut, hex("3243f6a8885a308d313198a2e0370734")) == hex("3925841d02dc09fbdc118597196a0b32"))
    }
  }

  it should "encrypt the FIPS-197 Appendix C.1 example" in {
    test(new AESEncrypt(128)) { dut =>
      pokeKey(dut, hexToBytes("000102030405060708090a0b0c0d0e0f"))
      assert(encrypt(dut, hex("00112233445566778899aabbccddeeff")) == hex("69c4e0d86a7b0430d8cdb78070b4c55a"))
    }
  }

  it should "take Nr + 1 cycles and not accept a new block while busy" in {
    test(new AESEncrypt(128)) { dut =>
      pokeKey(dut, hexToBytes("000102030405060708090a0b0c0d0e0f"))
      dut.io.out.ready.poke(false.B)
      dut.io.in.bits.poke(hex("00112233445566778899aabbccddeeff").U)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()
      //keep in.valid high with another block, it must be ignored while busy
      dut.io.in.bits.poke(0.U)
      for (_ <- 1 to 10) {
        dut.io.in.ready.expect(false.B)
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(hex("69c4e0d86a7b0430d8cdb78070b4c55a").U)
    }
  }

  it should "hold the ciphertext until out.ready, then take the next block in the same cycle" in {
    test(new AESEncrypt(128)) { dut =>
      pokeKey(dut, hexToBytes("2b7e151628aed2a6abf7158809cf4f3c"))
      dut.io.out.ready.poke(false.B)
      dut.io.in.bits.poke(hex("3243f6a8885a308d313198a2e0370734").U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(11)

      //nobody takes the result, so it stays
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.expect(hex("3925841d02dc09fbdc118597196a0b32").U)
        dut.io.in.ready.expect(false.B)
        dut.clock.step()
      }

      //take the result and give a new block in the same cycle
      pokeKey(dut, hexToBytes("000102030405060708090a0b0c0d0e0f"))
      dut.io.in.bits.poke(hex("00112233445566778899aabbccddeeff").U)
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step(10)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(hex("69c4e0d86a7b0430d8cdb78070b4c55a").U)
    }
  }

  it should "match the JDK AES for random keys and plaintexts" in {
    test(new AESEncrypt(128)) { dut =>
      forAll(bytesGen(16), bytesGen(16), minSuccessful(100)) { (key, pt) =>
        pokeKey(dut, key)
        val ct = encrypt(dut, bytesToBigInt(pt))
        assert(bigIntToBytes(ct, 16) == encryptJdk(key, pt))
      }
    }
  }

  for (name <- nistFiles) {
    it should s"pass the NIST AESAVS ECB $name test vectors" in {
      test(new AESEncrypt(128))(dut => runNistFile(dut, s"ECB${name}128.rsp"))
    }
  }

  it should s"pass the NIST AESAVS ECB Monte Carlo test (first $mctRecords records)" in {
    test(new AESEncrypt(128)) { dut =>
      for (v <- NistVectors.load("ECBMCT128.rsp").take(mctRecords)) {
        //each record: encrypt 1000 times, feeding the ciphertext back in as plaintext
        pokeKey(dut, v.key)
        var block = v.plaintext.head
        for (_ <- 0 until 1000) block = encrypt(dut, block)
        assert(block == v.ciphertext.head, s"ECBMCT128.rsp COUNT = ${v.count}")
      }
    }
  }

  //The core only depends on the key size through the number of rounds,
  //so check that the generator also works for AES-192 and AES-256
  for ((keySize, key, ct) <- Seq(
    (192, "000102030405060708090a0b0c0d0e0f1011121314151617", "dda97ca4864cdfe06eaf70a0ec0d7191"),
    (256, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "8ea2b7ca516745bfeafc49904b496089"))) {

    behavior of s"AESEncrypt (AES-$keySize)"

    it should s"encrypt the FIPS-197 Appendix C example" in {
      test(new AESEncrypt(keySize)) { dut =>
        pokeKey(dut, hexToBytes(key))
        assert(encrypt(dut, hex("00112233445566778899aabbccddeeff")) == hex(ct))
      }
    }

    for (name <- nistFiles) {
      it should s"pass the NIST AESAVS ECB $name test vectors" in {
        test(new AESEncrypt(keySize))(dut => runNistFile(dut, s"ECB$name$keySize.rsp"))
      }
    }
  }
}
