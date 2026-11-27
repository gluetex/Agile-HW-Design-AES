# 02201 Agile Hardware Design project
# Group 1 - Advanced Encryption Standard

This repository is for the group project in the course [02201 Agile Hardware Design](https://kurser.dtu.dk/course/02201) at the Technical University of Denmark (DTU).

The project is written using the Chisel3 library for Scala, to implement the Advanced Encryption Standard (AES) as defined in the specification published by the National Institute of Standards and Technology (NIST) [^1].
To ensure correctness of the implementation, the validation suite published by NIST [^2], was used as our 'golden model' to compare against, for the different algorithm operating modes that we implemented.

## Introduction / Motivation
Our goal was to see how cryptography standards works when it is built as a hardware. We picked AES because it is the most popular, it is used almost everywhere: in Wi-Fi, in website with HTTPS, and to protect data on disks. What we liked is that the whole algorithm is built from a few simple steps: replacing bytes, moving rows, mixing columns and XOR operations with key. These steps fit well with what hardware is good at.

Instead of building one fixed AES circuit, we wrote a generator in Chisel. You can choose the key size, mode, and the generator builds matching hardware for encryption and decryption.

#### Group Members:
- Timothy Canev (s263982)
- Emre Ertug (s252614)
- Johan Hägg Friis-Hasché (s265287)
- Jakub Koziara (s263983)
- Zbigniew Kruszwic (s263978)

## Features
The key features are outlined below, in no particular order:
- **Configurable key size**: the key size (128, 192 or 256 bits) is a design parameter. The generator builds the right hardware for each size, with 10, 12 or 14 rounds.
- **Encryption core**: encrypts one 128bit block. It does one AES round per clock cycle, so one block takes `Nr + 1` cycles.
- **Decryption core**: by using the inverse steps in reverse order we can get the original data back.
- **Key expansion**: turns the user's key into all round keys at once, so every round key is ready right away.
- **Operating modes**: besides plain single-block encryption (ECB), the project supports CBC, CFB (8bit and 128bit) and OFB modes established by NIST.
- **Ready/valid interface**: every module uses a simple valid/ready handshake, so blocks can be sent one after another without extra waiting.
- **Testing against official data**: our design is tested with unit tests, random tests against software model and Java built-in AES, and official NIST files.
- **Continuous integration**: all tests run automatically on GitHub Actions for every pull request.

## Dependencies
TODO

## Running the project
TODO

### Running the tests
TODO

### Generating Verilog
TODO

### Running on the FPGA
TODO

## Implementation details
### Overview
![Overview of the AES design](docs/overview.svg)
The cipher goes into `KeyExpansion` module which turns it into all the round keys at once. The data block (128bits) goes into the cipher core. The core keeps the block in a state register and runs it through one AES round per clock cycle, so a full block takes `Nr + 1` cycles.
Same core structure is used for encryption (`AESEncrypt`) and, with the inverse steps, for decryption (`AESDecrypt`). Around the core, a mode wrapper adds the logic for different operational modes.

### AES
TODO
#### State representation
TODO
#### Round operations
TODO
#### Key expansion
The `KeyExpansion` module turns the cypher key into `Nr + 1` round keys (11 for 128-AES), following FIPS-197 [^1]. 

The key size (128, 192 or 256 bits) is a parameter, so one generator covers three versions. Wrong sizes are rejected when the hardware is generated.

The module is combinational, so all round keys are ready at once. This makes decryption easier, because it needs keys in reverse. Cost of that is more S-boxes (40 for 128-AES) and little longer logic path.
It's tested with the FIPS-197 examples and with random keys against software model.
#### Encryption core
TODO
#### Decryption core
TODO

### Operating modes
TODO
#### ECB
TODO
#### CBC
TODO
#### CFB
TODO
#### OFB
TODO
### Interface (ready/valid)

### UART
TODO

### Controller
TODO

## Testing
TODO

## Results
TODO

### Resource usage
TODO

### Timing and throughput
TODO

## Agile development process
TODO

## Use of AI/LLM tools
Generative AI tools were used as a coding assistant and technical advisor, for example to explain parts of AES specification and suggest Chisel constructs. All AI generated sugestions were reviewed first by the group members.

## Remaining uncertainties and future work
TODO

## Sources
[^1]: National Institute of Standards and Technology (NIST). Advanced Encryption Standard (AES). [DOI](https://doi.org/https://doi.org/10.6028/NIST.FIPS.197-upd1)
[^2]: National Institute of Standards and Technology (NIST), Lawrence E. Basham III. The Advanced Encryption Standard Algorithm Validation Suite (AESAVS). 15th of November 2002. [URL](https://csrc.nist.gov/CSRC/media/Projects/Cryptographic-Algorithm-Validation-Program/documents/aes/AESAVS.pdf), last accessed 24th of November 2026.
[^3]: Joan Daemen and Vincent Rijmen. The Design of Rijndael: The Advanced Encryption Standard (AES). ISBN: 3-662-60769-7
