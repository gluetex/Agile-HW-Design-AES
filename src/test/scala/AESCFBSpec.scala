import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Gen, Shrink}
import org.scalatestplus.scalacheck._
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import AESReference._

class AESCFBSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {

  //The full Monte Carlo test is 100 x 1000 segments. AESCFB also simulates KeyExpansion every cycle,
  //so one record takes ~40 s and by default only the first one is run. Set AES_FULL_MCT=1 to run all of them.
  val mctRecords: Int = if (sys.env.contains("AES_FULL_MCT")) Int.MaxValue else 1

  def setup(dut: AESCFB, key: Seq[Int], iv: BigInt, decrypt: Boolean): Unit = {
    dut.io.key.poke(bytesToBigInt(key).U(dut.keySize.W))
    dut.io.iv.poke(iv.U(128.W))
    dut.io.decrypt.poke(decrypt.B)
  }

  //Send one segment in, wait for the result and take it
  def process(dut: AESCFB, segment: BigInt, first: Boolean): BigInt = {
    val nr = AESEncrypt.rounds(dut.keySize)
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.data.poke(segment.U(dut.s.W))
    dut.io.in.bits.first.poke(first.B)
    dut.io.in.valid.poke(true.B)
    while (!dut.io.in.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
    var cycles = 1
    while (!dut.io.out.valid.peek().litToBoolean) {
      dut.clock.step()
      cycles += 1
      assert(cycles <= nr + 1, "no output after Nr + 1 cycles")
    }
    val result = dut.io.out.bits.peek().litValue
    dut.clock.step()
    result
  }

  //A whole message, the first segment starts from the IV
  def processMessage(dut: AESCFB, segments: Seq[BigInt]): Seq[BigInt] =
    segments.zipWithIndex.map { case (seg, i) => process(dut, seg, i == 0) }

  def hex(s: String): BigInt = BigInt(s, 16)

  def segments(hex: String, s: Int): Seq[BigInt] = hex.grouped(s / 4).map(BigInt(_, 16)).toSeq

  //Independent reference: the CFB mode in the JDK
  def cfbJdk(key: Seq[Int], iv: Seq[Int], data: Seq[Int], s: Int, decrypt: Boolean): Seq[Int] = {
    val cipher = Cipher.getInstance(s"AES/CFB$s/NoPadding")
    val mode = if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
    cipher.init(mode, new SecretKeySpec(key.map(_.toByte).toArray, "AES"), new IvParameterSpec(iv.map(_.toByte).toArray))
    cipher.doFinal(data.map(_.toByte).toArray).map(_ & 0xff).toSeq
  }

  //Both parts of a NIST file: [ENCRYPT] plaintext -> ciphertext, [DECRYPT] ciphertext -> plaintext
  def runNistFile(dut: AESCFB, file: String): Unit =
    for (decrypt <- Seq(false, true)) {
      val section = if (decrypt) "DECRYPT" else "ENCRYPT"
      for (v <- NistVectors.load(file, section, dut.s)) {
        setup(dut, v.key, v.iv.get, decrypt)
        val (in, expected) = if (decrypt) (v.ciphertext, v.plaintext) else (v.plaintext, v.ciphertext)
        assert(processMessage(dut, in) == expected, s"$file [$section] COUNT = ${v.count}")
      }
    }

  val nistFiles = Seq("GFSbox", "KeySbox", "VarTxt", "VarKey", "MMT")

  val bytesGen: Int => Gen[Seq[Int]] = n => Gen.listOfN(n, Gen.choose(0, 255))
  implicit val noShrink: Shrink[Seq[Int]] = Shrink.shrinkAny

  //NIST SP 800-38A F.3.7 (CFB8-AES128) and F.3.13 (CFB128-AES128), the decryption examples are the same in reverse
  val spKey = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
  val spIv = hex("000102030405060708090a0b0c0d0e0f")
  val spExamples = Map(
    8 -> ("6bc1bee22e409f96e93d7e117393172aae2d", "3b79424c9c0dd436bace9e0ed4586a4f32b9"),
    128 -> ("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
            "3b3fd92eb72dad20333449f8e83cfb4ac8a64537a0b3a93fcde3cdad9f1ce58b" +
            "26751f67a3cbb140b1808cf187a4f4dfc04b05357c5d1c0eeac4c66f9ff7f2e6"))

  for (s <- Seq(128, 8)) {
    val (spPlaintext, spCiphertext) = spExamples(s)

    behavior of s"AESCFB (CFB$s, AES-128)"

    it should "encrypt the NIST SP 800-38A example" in {
      test(new AESCFB(128, s)) { dut =>
        setup(dut, spKey, spIv, decrypt = false)
        assert(processMessage(dut, segments(spPlaintext, s)) == segments(spCiphertext, s))
      }
    }

    it should "decrypt the NIST SP 800-38A example" in {
      test(new AESCFB(128, s)) { dut =>
        setup(dut, spKey, spIv, decrypt = true)
        assert(processMessage(dut, segments(spCiphertext, s)) == segments(spPlaintext, s))
      }
    }

    it should "take Nr + 1 cycles per segment and take the next segment in the same cycle" in {
      test(new AESCFB(128, s)) { dut =>
        setup(dut, spKey, spIv, decrypt = false)
        dut.io.out.ready.poke(true.B)
        dut.io.in.valid.poke(true.B)
        for (((pt, ct), i) <- segments(spPlaintext, s).zip(segments(spCiphertext, s)).zipWithIndex) {
          //the next segment is waiting the whole time, it is only taken when the output is taken
          dut.io.in.bits.data.poke(pt.U)
          dut.io.in.bits.first.poke((i == 0).B)
          dut.io.in.ready.expect(true.B)
          dut.clock.step()
          for (_ <- 1 to 10) {
            dut.io.in.ready.expect(false.B)
            dut.io.out.valid.expect(false.B)
            dut.clock.step()
          }
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.expect(ct.U)
        }
      }
    }

    it should "hold the output until out.ready" in {
      test(new AESCFB(128, s)) { dut =>
        setup(dut, spKey, spIv, decrypt = false)
        val pt = segments(spPlaintext, s)
        val ct = segments(spCiphertext, s)
        dut.io.out.ready.poke(false.B)
        dut.io.in.bits.data.poke(pt(0).U)
        dut.io.in.bits.first.poke(true.B)
        dut.io.in.valid.poke(true.B)
        dut.clock.step()
        dut.io.in.bits.data.poke(pt(1).U)
        dut.io.in.bits.first.poke(false.B)
        dut.clock.step(10)

        //nobody takes the output, so it stays and the next segment has to wait
        for (_ <- 0 until 5) {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.expect(ct(0).U)
          dut.io.in.ready.expect(false.B)
          dut.clock.step()
        }

        //take it, the waiting segment goes in at the same time and still chains correctly
        dut.io.out.ready.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step(10)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.expect(ct(1).U)
      }
    }

    for (keySize <- Seq(128, 192, 256)) {
      behavior of s"AESCFB (CFB$s, AES-$keySize)"

      //every message starts again from a new IV, so this also checks the first flag
      it should "match the JDK for random keys, IVs and messages, encrypting and decrypting" in {
        test(new AESCFB(keySize, s)) { dut =>
          val messageGen = Gen.choose(1, 8).flatMap(n => bytesGen(n * s / 8))
          forAll(bytesGen(keySize / 8), bytesGen(16), messageGen, Gen.oneOf(false, true), minSuccessful(100)) {
            (key, iv, message, decrypt) =>
              setup(dut, key, bytesToBigInt(iv), decrypt)
              val out = processMessage(dut, message.grouped(s / 8).map(bytesToBigInt).toSeq)
              assert(out.flatMap(bigIntToBytes(_, s / 8)) == cfbJdk(key, iv, message, s, decrypt))
          }
        }
      }

      for (name <- nistFiles) {
        it should s"pass the NIST AESAVS CFB$s $name test vectors" in {
          test(new AESCFB(keySize, s))(dut => runNistFile(dut, s"CFB$s$name$keySize.rsp"))
        }
      }
    }

    behavior of s"AESCFB (CFB$s, AES-128)"

    //AESAVS Monte Carlo test: 1000 segments in one message. The next input is the IV segment by segment
    //for the first 128 / s segments, after that it is the output from 128 / s segments before.
    //The record holds the last output.
    for (decrypt <- Seq(false, true)) {
      val section = if (decrypt) "DECRYPT" else "ENCRYPT"
      it should s"pass the NIST AESAVS CFB$s Monte Carlo test, $section (first $mctRecords records)" in {
        test(new AESCFB(128, s)) { dut =>
          val n = 128 / s
          for (v <- NistVectors.load(s"CFB${s}MCT128.rsp", section, s).take(mctRecords)) {
            val iv = v.iv.get
            setup(dut, v.key, iv, decrypt)
            val (start, expected) = if (decrypt) (v.ciphertext.head, v.plaintext.head) else (v.plaintext.head, v.ciphertext.head)
            val outputs = scala.collection.mutable.ArrayBuffer.empty[BigInt]
            var input = start
            for (j <- 0 until 1000) {
              outputs += process(dut, input, j == 0)
              input = if (j < n) (iv >> (128 - s * (j + 1))) & ((BigInt(1) << s) - 1) else outputs(j - n)
            }
            assert(outputs.last == expected, s"CFB${s}MCT128.rsp [$section] COUNT = ${v.count}")
          }
        }
      }
    }
  }
}
