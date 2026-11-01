import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Gen, Shrink}
import org.scalatestplus.scalacheck._
import AESReference._

//KeyExpansion feeds both cores, AESEncrypt's output goes straight into AESDecrypt.
//So the output should be the input again.
class AESEncryptDecrypt(val keySize: Int) extends Module {
  val io = IO(new Bundle {
    val key = Input(UInt(keySize.W))
    val in = Flipped(Decoupled(UInt(128.W)))
    val out = Decoupled(UInt(128.W))
  })
  val keyExpansion = Module(new KeyExpansion(keySize))
  val encrypt = Module(new AESEncrypt(keySize))
  val decrypt = Module(new AESDecrypt(keySize))

  keyExpansion.io.key := io.key
  encrypt.io.roundKeys := keyExpansion.io.roundKeys
  decrypt.io.roundKeys := keyExpansion.io.roundKeys
  encrypt.io.in <> io.in
  decrypt.io.in <> encrypt.io.out
  io.out <> decrypt.io.out
}

class AESDecryptSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {

  //The full Monte Carlo test is 100 x 1000 decryptions and takes a while in simulation,
  //so by default only the first few records are run. Set AES_FULL_MCT=1 to run all of them.
  val mctRecords: Int = if (sys.env.contains("AES_FULL_MCT")) Int.MaxValue else 5

  //The round keys come from the software model, so only the decryption core is tested here
  def pokeKey(dut: AESDecrypt, key: Seq[Int]): Unit = {
    val roundKeys = expandKey(key)
    for (k <- 0 to dut.nr; i <- 0 to 3; j <- 0 to 3)
      dut.io.roundKeys(k).rows(i)(j).poke(roundKeys(k)(i)(j).U)
  }

  //Send one block in, wait for the result and take it
  def decrypt(dut: AESDecrypt, block: BigInt): BigInt = {
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
      assert(cycles <= dut.nr + 1, "no plaintext after Nr + 1 cycles")
    }
    val result = dut.io.out.bits.peek().litValue
    dut.clock.step()
    result
  }

  def hex(s: String): BigInt = BigInt(s, 16)

  def runNistFile(dut: AESDecrypt, file: String): Unit =
    for (v <- NistVectors.load(file, "DECRYPT")) {
      pokeKey(dut, v.key)
      //ECB: every block is decrypted on its own with the same key
      for ((ct, pt) <- v.ciphertext.zip(v.plaintext)) {
        assert(decrypt(dut, ct) == pt, s"$file [DECRYPT] COUNT = ${v.count}")
      }
    }

  val nistFiles = Seq("GFSbox", "KeySbox", "VarTxt", "VarKey", "MMT")

  val bytesGen: Int => Gen[Seq[Int]] = n => Gen.listOfN(n, Gen.choose(0, 255))
  implicit val noShrink: Shrink[Seq[Int]] = Shrink.shrinkAny

  behavior of "AESDecrypt (AES-128)"

  it should "decrypt the FIPS-197 Appendix B example" in {
    test(new AESDecrypt(128)) { dut =>
      pokeKey(dut, hexToBytes("2b7e151628aed2a6abf7158809cf4f3c"))
      assert(decrypt(dut, hex("3925841d02dc09fbdc118597196a0b32")) == hex("3243f6a8885a308d313198a2e0370734"))
    }
  }

  it should "decrypt the FIPS-197 Appendix C.1 example" in {
    test(new AESDecrypt(128)) { dut =>
      pokeKey(dut, hexToBytes("000102030405060708090a0b0c0d0e0f"))
      assert(decrypt(dut, hex("69c4e0d86a7b0430d8cdb78070b4c55a")) == hex("00112233445566778899aabbccddeeff"))
    }
  }

  it should "take Nr + 1 cycles and not accept a new block while busy" in {
    test(new AESDecrypt(128)) { dut =>
      pokeKey(dut, hexToBytes("000102030405060708090a0b0c0d0e0f"))
      dut.io.out.ready.poke(false.B)
      dut.io.in.bits.poke(hex("69c4e0d86a7b0430d8cdb78070b4c55a").U)
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
      dut.io.out.bits.expect(hex("00112233445566778899aabbccddeeff").U)
    }
  }

  it should "hold the plaintext until out.ready, then take the next block in the same cycle" in {
    test(new AESDecrypt(128)) { dut =>
      pokeKey(dut, hexToBytes("2b7e151628aed2a6abf7158809cf4f3c"))
      dut.io.out.ready.poke(false.B)
      dut.io.in.bits.poke(hex("3925841d02dc09fbdc118597196a0b32").U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(11)

      //nobody takes the result, so it stays
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.expect(hex("3243f6a8885a308d313198a2e0370734").U)
        dut.io.in.ready.expect(false.B)
        dut.clock.step()
      }

      //take the result and give a new block in the same cycle
      pokeKey(dut, hexToBytes("000102030405060708090a0b0c0d0e0f"))
      dut.io.in.bits.poke(hex("69c4e0d86a7b0430d8cdb78070b4c55a").U)
      dut.io.in.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step(10)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(hex("00112233445566778899aabbccddeeff").U)
    }
  }

  it should "match the JDK AES for random keys and ciphertexts" in {
    test(new AESDecrypt(128)) { dut =>
      forAll(bytesGen(16), bytesGen(16), minSuccessful(100)) { (key, ct) =>
        pokeKey(dut, key)
        val pt = decrypt(dut, bytesToBigInt(ct))
        assert(bigIntToBytes(pt, 16) == decryptJdk(key, ct))
      }
    }
  }

  for (name <- nistFiles) {
    it should s"pass the NIST AESAVS ECB $name decrypt test vectors" in {
      test(new AESDecrypt(128))(dut => runNistFile(dut, s"ECB${name}128.rsp"))
    }
  }

  it should s"pass the NIST AESAVS ECB Monte Carlo decrypt test (first $mctRecords records)" in {
    test(new AESDecrypt(128)) { dut =>
      for (v <- NistVectors.load("ECBMCT128.rsp", "DECRYPT").take(mctRecords)) {
        //each record: decrypt 1000 times, feeding the plaintext back in as ciphertext
        pokeKey(dut, v.key)
        var block = v.ciphertext.head
        for (_ <- 0 until 1000) block = decrypt(dut, block)
        assert(block == v.plaintext.head, s"ECBMCT128.rsp [DECRYPT] COUNT = ${v.count}")
      }
    }
  }

  //Like AESEncrypt, the core only depends on the key size through the number of rounds
  for ((keySize, key, ct) <- Seq(
    (192, "000102030405060708090a0b0c0d0e0f1011121314151617", "dda97ca4864cdfe06eaf70a0ec0d7191"),
    (256, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "8ea2b7ca516745bfeafc49904b496089"))) {

    behavior of s"AESDecrypt (AES-$keySize)"

    it should s"decrypt the FIPS-197 Appendix C example" in {
      test(new AESDecrypt(keySize)) { dut =>
        pokeKey(dut, hexToBytes(key))
        assert(decrypt(dut, hex(ct)) == hex("00112233445566778899aabbccddeeff"))
      }
    }

    it should "match the JDK AES for random keys and ciphertexts" in {
      test(new AESDecrypt(keySize)) { dut =>
        forAll(bytesGen(keySize / 8), bytesGen(16), minSuccessful(100)) { (key, ct) =>
          pokeKey(dut, key)
          val pt = decrypt(dut, bytesToBigInt(ct))
          assert(bigIntToBytes(pt, 16) == decryptJdk(key, ct))
        }
      }
    }

    for (name <- nistFiles) {
      it should s"pass the NIST AESAVS ECB $name decrypt test vectors" in {
        test(new AESDecrypt(keySize))(dut => runNistFile(dut, s"ECB$name$keySize.rsp"))
      }
    }
  }

  //Encrypt and then decrypt in hardware, with the round keys from the KeyExpansion module
  for (keySize <- Seq(128, 192, 256)) {
    behavior of s"AESEncrypt + AESDecrypt (AES-$keySize)"

    it should "give back the original block for random keys and plaintexts" in {
      test(new AESEncryptDecrypt(keySize)) { dut =>
        val nr = AESEncrypt.rounds(keySize)
        dut.io.out.ready.poke(true.B)
        forAll(bytesGen(keySize / 8), bytesGen(16), minSuccessful(100)) { (key, pt) =>
          dut.io.key.poke(bytesToBigInt(key).U(keySize.W))
          dut.io.in.bits.poke(bytesToBigInt(pt).U(128.W))
          dut.io.in.valid.poke(true.B)
          while (!dut.io.in.ready.peek().litToBoolean) dut.clock.step()
          dut.clock.step()
          dut.io.in.valid.poke(false.B)
          //Nr + 1 cycles to encrypt, then Nr + 1 to decrypt
          var cycles = 1
          while (!dut.io.out.valid.peek().litToBoolean) {
            dut.clock.step()
            cycles += 1
            assert(cycles <= 2 * (nr + 1), "no output after 2 * (Nr + 1) cycles")
          }
          assert(dut.io.out.bits.peek().litValue == bytesToBigInt(pt))
          dut.clock.step()
        }
      }
    }
  }
}
