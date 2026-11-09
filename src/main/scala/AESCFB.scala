import chisel3._
import chisel3.util._

//One segment of the message going into AESCFB
class CFBSegment(val segmentSize: Int) extends Bundle {
  val data = UInt(segmentSize.W) //plaintext when encrypting, ciphertext when decrypting
  val first = Bool()             //first segment of a message: start again from io.iv
}

//AES in Cipher Feedback mode, CFB-s (NIST SP 800-38A 6.3), with s = segmentSize bits per step.
//segmentSize 128 is CFB128 (a whole block per step), 8 is CFB8 (one byte per step).
//
//The input block I starts as the IV. For every segment:
//  O = AES(I), output = input xor the top s bits of O,
//  then the ciphertext segment C is shifted into I from the right: I = (I << s) | C.
//Encryption and decryption only differ in which side is the ciphertext,
//so both directions use AES encryption, AESDecrypt is not needed.
//
//key, iv and decrypt have to stay the same while a message is being processed.
//Every segment takes Nr + 1 cycles (one AES encryption) from in.fire to out.valid.
//The next segment needs the ciphertext of this one, so only one segment is worked on at a time,
//but the next one can be taken in the same cycle the output is taken.
class AESCFB(val keySize: Int = 128, val segmentSize: Int = 128) extends Module {
  require(Seq(8, 128).contains(segmentSize), "only CFB8 and CFB128 are supported")
  val s = segmentSize

  val io = IO(new Bundle {
    val key = Input(UInt(keySize.W)) //byte 0 of the key is the MSB, like in the NIST hex strings
    val iv = Input(UInt(128.W))
    val decrypt = Input(Bool())
    val in = Flipped(Decoupled(new CFBSegment(s)))
    val out = Decoupled(UInt(s.W)) //ciphertext when encrypting, plaintext when decrypting
  })

  val aes = Module(new AES(keySize))
  aes.io.key := io.key

  val block = Reg(UInt(128.W))   //I of the segment being worked on, the next I once its output is taken
  val segment = Reg(UInt(s.W))   //input of the segment being worked on

  val result = segment ^ aes.io.out.bits(127, 128 - s)
  val cipherSegment = Mux(io.decrypt, segment, result)
  val nextBlock = if (s == 128) cipherSegment else Cat(block(127 - s, 0), cipherSegment)

  //I for a new segment: the IV for the first one of a message, otherwise the chained block.
  //If the last output is taken in this same cycle, block is not updated yet, so nextBlock is used.
  val chained = Mux(aes.io.out.valid, nextBlock, block)
  val inBlock = Mux(io.in.bits.first, io.iv, chained)

  //AES only takes a new block when it is idle, or when its output is taken in the same cycle
  aes.io.in.valid := io.in.valid
  aes.io.in.bits := inBlock
  io.in.ready := aes.io.in.ready

  io.out.valid := aes.io.out.valid
  io.out.bits := result
  aes.io.out.ready := io.out.ready

  when(io.out.fire) {
    block := nextBlock
  }

  when(io.in.fire) {
    block := inBlock
    segment := io.in.bits.data
  }
}
