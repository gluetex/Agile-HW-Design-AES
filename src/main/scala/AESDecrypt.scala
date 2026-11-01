import chisel3._
import chisel3.util._

//AES decryption of one 128-bit block (FIPS-197 5.3, InvCipher), one round per clock cycle.
//
//It is AESEncrypt run backwards: every step is replaced by its inverse, and the round keys
//are used from the last one to the first. Like in AESEncrypt, the key size only changes
//the number of rounds, so the same core works for AES-128, 192 and 256.
//
//roundKeys is the same as for AESEncrypt (straight from KeyExpansion), the core picks
//them in reverse order itself. The round keys must stay the same while a block is being decrypted.
//
//Timing is the same as AESEncrypt: when in.fire, the block goes through round 0 (just AddRoundKey
//with the last round key). Rounds 1..Nr then take one cycle each, so out.valid goes high Nr + 1 cycles
//after in.fire. out.bits is held until out.ready, and a new block can be taken in the same cycle.
class AESDecrypt(val keySize: Int = 128) extends Module {
  require(Seq(128, 192, 256).contains(keySize), "AES key size must be 128, 192 or 256")
  val nr = AESEncrypt.rounds(keySize)

  val io = IO(new Bundle {
    val roundKeys = Input(Vec(nr + 1, new State))
    val in = Flipped(Decoupled(UInt(128.W))) //ciphertext
    val out = Decoupled(UInt(128.W))         //plaintext
  })

  val state = Reg(new State)
  val round = RegInit(0.U(log2Ceil(nr + 1).W))
  val busy = RegInit(false.B)
  val done = RegInit(false.B)

  val invShiftRows = Module(new InvShiftRows)
  val invSubBytes = Module(new InvSubBytes)
  val addRoundKey = Module(new AddRoundKey)
  val invMixColumns = Module(new InvMixColumns)

  //One round: InvShiftRows -> InvSubBytes -> AddRoundKey -> InvMixColumns
  invShiftRows.io.in := state
  invSubBytes.io.in := invShiftRows.io.out

  //When not busy, AddRoundKey is used for round 0 on the new ciphertext (round is 0 then).
  //Round r uses round key Nr - r, so round 0 uses the last key and round Nr the first one.
  addRoundKey.io.in := Mux(busy, invSubBytes.io.out, AESBlock.toState(io.in.bits))
  addRoundKey.io.key := io.roundKeys(nr.U - round)
  invMixColumns.io.in := addRoundKey.io.out

  //Round 0 and the last round skip InvMixColumns.
  val roundOut = Mux(!busy || round === nr.U, addRoundKey.io.out, invMixColumns.io.out)

  io.in.ready := !busy && (!done || io.out.ready)
  io.out.valid := done
  io.out.bits := AESBlock.fromState(state)

  when(io.out.fire) {
    done := false.B
  }

  when(io.in.fire) {
    state := roundOut
    round := 1.U
    busy := true.B
  }.elsewhen(busy) {
    state := roundOut
    round := round + 1.U
    when(round === nr.U) {
      round := 0.U
      busy := false.B
      done := true.B
    }
  }
}
