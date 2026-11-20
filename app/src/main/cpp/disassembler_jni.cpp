#include <jni.h>
#include <capstone/capstone.h>
#include <cstdint>
#include <cstdio>
#include <sstream>
#include <vector>

namespace {
std::string quote(const char *s) {
    std::string out = "\"";
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(s); *p; ++p) {
        switch (*p) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (*p < 32) { char escaped[7]; snprintf(escaped, sizeof(escaped), "\\u%04x", *p); out += escaped; }
                else out += static_cast<char>(*p);
        }
    }
    return out + "\"";
}
std::string hex(uint64_t value) { std::ostringstream s; s << "0x" << std::hex << value; return s.str(); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kernelai_app_analysis_Arm64Disassembler_nativeDecode(JNIEnv *env, jobject, jbyteArray input, jlong base, jint count) {
    const jsize length = input ? env->GetArrayLength(input) : 0;
    const auto address = static_cast<uint64_t>(base);
    if (length < 4 || length > 1024 || length % 4 || count < 1 || count > 256 || address % 4 || address > UINT64_MAX - length)
        return env->NewStringUTF("{\"is_error\":true,\"message\":\"Invalid ARM64 decode range\"}");
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    csh handle;
    if (cs_open(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN, &handle) != CS_ERR_OK)
        return env->NewStringUTF("{\"is_error\":true,\"message\":\"Capstone initialization failed\"}");
    cs_option(handle, CS_OPT_DETAIL, CS_OPT_ON);
    cs_insn *instruction = cs_malloc(handle);
    if (!instruction) { cs_close(&handle); return env->NewStringUTF("{\"is_error\":true,\"message\":\"Capstone allocation failed\"}"); }
    std::ostringstream result;
    result << "{\"engine\":\"Capstone 5.0.6\",\"architecture\":\"arm64\",\"instructions\":[";
    size_t consumed = 0;
    for (int index = 0; index < count && consumed + 4 <= bytes.size(); ++index) {
        if (index) result << ',';
        const uint8_t *code = bytes.data() + consumed;
        size_t remaining = bytes.size() - consumed;
        uint64_t pc = address + consumed;
        const bool decoded = cs_disasm_iter(handle, &code, &remaining, &pc, instruction);
        char raw[9];
        snprintf(raw, sizeof(raw), "%02x%02x%02x%02x", bytes[consumed], bytes[consumed + 1], bytes[consumed + 2], bytes[consumed + 3]);
        result << "{\"address\":" << quote(hex(address + consumed).c_str()) << ",\"bytes\":" << quote(raw)
               << ",\"size\":4,\"decoded\":" << (decoded ? "true" : "false");
        if (decoded) {
            result << ",\"mnemonic\":" << quote(instruction->mnemonic) << ",\"operands\":" << quote(instruction->op_str);
            const bool branch = cs_insn_group(handle, instruction, CS_GRP_JUMP) || cs_insn_group(handle, instruction, CS_GRP_CALL);
            result << ",\"branch\":" << (branch ? "true" : "false");
            if (branch && instruction->detail) {
                const auto &detail = instruction->detail->arm64;
                for (uint8_t n = 0; n < detail.op_count; ++n)
                    if (detail.operands[n].type == ARM64_OP_IMM)
                        result << ",\"target\":" << quote(hex(static_cast<uint64_t>(detail.operands[n].imm)).c_str());
            }
        } else {
            result << ",\"mnemonic\":\".inst\",\"operands\":\"unallocated or unsupported encoding\"";
        }
        result << '}';
        consumed += 4;
    }
    result << "],\"bytes_consumed\":" << consumed << ",\"next_address\":" << quote(hex(address + consumed).c_str()) << '}';
    cs_free(instruction, 1);
    cs_close(&handle);
    return env->NewStringUTF(result.str().c_str());
}
