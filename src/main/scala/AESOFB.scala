import chisel3._
import chisel3.util._

//one block going into the OFB module
class OFBBlock extends Bundle {
  val data = UInt(128.W) //plaintext or ciphertext, same thing in OFB
  val first = Bool()     //true for the first block, then we start from the iv
}

//OFB: encrypt the iv, then keep encrypting the result and xor it with the data. Decrypting is the exact same thing
class AESOFB(val keySize: Int = 128) extends Module {
  val io = IO(new Bundle {
    val key = Input(UInt(keySize.W)) //first byte is the MSB, same as the NIST hex strings
    val iv = Input(UInt(128.W))
    val in = Flipped(Decoupled(new OFBBlock))
    val out = Decoupled(UInt(128.W)) //the other one of plaintext/ciphertext
  })

  val aes = Module(new AES(keySize))
  aes.io.key := io.key

  val keystream = Reg(UInt(128.W)) //the last keystream block
  val data = Reg(UInt(128.W))      //the data block we are working on

  //if the output gets taken this cycle the keystream reg is not updated yet, so use aes out directly
  val chained = Mux(aes.io.out.valid, aes.io.out.bits, keystream)
  val inBlock = Mux(io.in.bits.first, io.iv, chained)

  //AES is ready when it is idle or when its output is being taken
  aes.io.in.valid := io.in.valid
  aes.io.in.bits := inBlock
  io.in.ready := aes.io.in.ready

  io.out.valid := aes.io.out.valid
  io.out.bits := data ^ aes.io.out.bits
  aes.io.out.ready := io.out.ready

  when(io.out.fire) {
    keystream := aes.io.out.bits
  }

  when(io.in.fire) {
    keystream := inBlock
    data := io.in.bits.data
  }
}
