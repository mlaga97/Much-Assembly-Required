package net.simon987.server.assembly;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JsePlatform;

import net.simon987.server.assembly.*;

public class LuaMAR {
    private CPU cpu;

    public LuaMAR(CPU cpu) {
        this.cpu = cpu;
    }

    public LuaValue init() {
        LuaValue library = LuaValue.tableOf();

        LuaRegisters reg = new LuaRegisters();
        LuaMemory mem = new LuaMemory();

        library.set("registers", reg.init());
        library.set("memory", mem.init());
        library.set("hwi", new LuaHWI());

        return library;
    }

    public class LuaRegisters {
        public LuaValue init() {
            LuaValue library = LuaValue.tableOf();

            library.set("getValue", new getValue());
            library.set("setValue", new setValue());

            return library;
        }

        public class getValue extends OneArgFunction {
            public LuaValue call(LuaValue registerName) {
                return LuaValue.valueOf(cpu.getRegisterSet().getRegister(registerName.checkjstring()).getValue());
            }
        }

        public class setValue extends TwoArgFunction {
            public LuaValue call(LuaValue registerName, LuaValue value) {
                cpu.getRegisterSet().getRegister(registerName.checkjstring()).setValue(value.checkint());
                return LuaValue.valueOf(1);
            }
        }
    }

    public class LuaMemory {
        public LuaValue init() {
            LuaValue library = LuaValue.tableOf();

            library.set("get", new get());
            library.set("set", new set());
            library.set("write", new write());

            return library;
        }

        public class get extends OneArgFunction {
            public LuaValue call(LuaValue address) {
                return LuaValue.valueOf(cpu.getMemory().get(address.checkint()));
            }
        }

        public class set extends TwoArgFunction {
            public LuaValue call(LuaValue address, LuaValue value) {
                cpu.getMemory().set(address.checkint(), value.checkint());
                return LuaValue.valueOf(1);
            }
        }

        //TODO: Actually write
        public class write extends ZeroArgFunction {
            public LuaValue call() {
                return LuaValue.valueOf(-1);
            }
        }
        
    }

    public class LuaHWI extends OneArgFunction {
        public LuaValue call(LuaValue id) {
            cpu.hardwareInterrupt(id.checkint());
            return LuaValue.valueOf(1);
        }
    }
}
