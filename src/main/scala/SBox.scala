import chisel3._

//AES S-box (FIPS-197 5.1.1)
//We calculate the table instead of typing all 256 values by hand
//S(b) = affine(inverse(b))
object SBox {
  //Multiply in GF(2^8), mod x^8 + x^4 + x^3 + x + 1
  private def mul(a: Int, b: Int): Int = {
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
  
  //Inverse in GF(2^8), 0 has no inverse so it just maps to 0
  private def inverse(a: Int): Int =
  if (a == 0) 0 else (1 until 256).find(b => mul(a, b) == 1).get
  
  private def rotl(b: Int, n: Int): Int = ((b << n) | (b >> (8 - n))) & 0xff
  
  private def affine(b: Int): Int =
  b ^ rotl(b, 1) ^ rotl(b, 2) ^ rotl(b, 3) ^ rotl(b, 4) ^ 0x63
  
  val table: Seq[Int] = (0 until 256).map(b => affine(inverse(b)))
  
  //All 256 entries packed into one 2048-bit constant, entry k is bits 8k+7..8k
  private val packed: BigInt = table.zipWithIndex.map { case (v, k) => BigInt(v) << (8 * k) }.sum

  //Look up one byte (can also be used for SubWord in KeyExpansion)
  //Shifting the constant gives the same hardware as indexing a Vec of the 256 values,
  //but simulates ~400x faster in chiseltest (the Vec version made the AES tests take hours)
  def apply(in: UInt): UInt = (packed.U(2048.W) >> (in(7, 0) ## 0.U(3.W)))(7, 0)
}
