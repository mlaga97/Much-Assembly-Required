package net.simon987.server.assembly;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JsePlatform;
import net.simon987.server.assembly.LuaMAR;
import net.simon987.server.GameServer;
import net.simon987.server.ServerConfiguration;
import net.simon987.server.assembly.exception.CancelledException;
import net.simon987.server.assembly.instruction.*;
import net.simon987.server.event.CpuInitialisationEvent;
import net.simon987.server.event.GameEvent;
import net.simon987.server.io.MongoSerialisable;
import net.simon987.server.logging.LogManager;
import net.simon987.server.user.User;

import java.util.HashMap;

/**
 * CPU: Central Processing Unit. A CPU is capable of reading bytes from
 * a Memory object and execute them. A CPU object holds registers objects &
 * a Memory object.
 */
public class CPU implements MongoSerialisable {

    /**
     *
     */
    private Status status;

    /**
     * Memory associated with the CPU, 64kb max
     */
    private Memory memory;

    /**
     * set of instructions of this CPU
     */
    private InstructionSet instructionSet;

    /**
     * set of registers of this CPU
     */
    private RegisterSet registerSet;

    /**
     * Offset of the code segment. The code starts to get
     * executed at this address each tick. Defaults to org_offset@config.properties
     */
    private int codeSegmentOffset;

    /**
     * Instruction pointer, always points to the next instruction
     */
    private int ip;

    /**
     * List of attached hardware, 'modules'
     */
    private HashMap<Integer, CpuHardware> attachedHardware;

    private ServerConfiguration config;

    private int registerSetSize;

    private static final char EXECUTION_COST_ADDR = 0x0050;
    private static final char EXECUTED_INS_ADDR = 0x0051;

    /**
     * Creates a new CPU
     */
    public CPU(ServerConfiguration config, User user) throws CancelledException {
        this.config = config;
        instructionSet = new DefaultInstructionSet();
        registerSet = new DefaultRegisterSet();
        attachedHardware = new HashMap<>();
        codeSegmentOffset = config.getInt("org_offset");

        instructionSet.add(new JmpInstruction(this));
        instructionSet.add(new JnzInstruction(this));
        instructionSet.add(new JzInstruction(this));
        instructionSet.add(new JgInstruction(this));
        instructionSet.add(new JgeInstruction(this));
        instructionSet.add(new JleInstruction(this));
        instructionSet.add(new JlInstruction(this));
        instructionSet.add(new PushInstruction(this));
        instructionSet.add(new PopInstruction(this));
        instructionSet.add(new CallInstruction(this));
        instructionSet.add(new RetInstruction(this));
        instructionSet.add(new MulInstruction(this));
        instructionSet.add(new DivInstruction(this));
        instructionSet.add(new JnsInstruction(this));
        instructionSet.add(new JsInstruction(this));
        instructionSet.add(new HwiInstruction(this));
        instructionSet.add(new HwqInstruction(this));
        instructionSet.add(new XchgInstruction(this));
        instructionSet.add(new JcInstruction(this));
        instructionSet.add(new JncInstruction(this));
        instructionSet.add(new JnoInstruction(this));
        instructionSet.add(new JoInstruction(this));
        instructionSet.add(new PushfInstruction(this));
        instructionSet.add(new PopfInstruction(this));

        status = new Status();
        memory = new Memory(config.getInt("memory_size"));

        GameEvent event = new CpuInitialisationEvent(this, user);
        GameServer.INSTANCE.getEventDispatcher().dispatch(event);
        if (event.isCancelled()) {
            throw new CancelledException();
        }
    }

    public void reset() {
        status.clear();
        ip = codeSegmentOffset;
    }

    public int execute(int timeout) {

        long startTime = System.currentTimeMillis();
        int counter = 0;
        status.clear();

        registerSetSize = registerSet.size();

        // Lua Stuff
        Globals globals = JsePlatform.standardGlobals();
        LuaMAR lm = new LuaMAR(this);
        globals.set("mar", lm.init()); 
        LuaValue chunk = globals.loadfile("./script.lua");
        chunk.call();

        // status.breakFlag = true;
        while (!status.isBreakFlag()) {
            counter++;

            if (counter % 10000 == 0) {
                if (System.currentTimeMillis() > (startTime + timeout)) {
                    LogManager.LOGGER.fine("CPU Timeout " + this + " after " + counter + "instructions (" + timeout + "ms): " + (double) counter / ((double) timeout / 1000) / 1000000 + "MHz");

                    //Write execution cost and instruction count to memory
                    memory.set(EXECUTION_COST_ADDR, timeout);
                    memory.set(EXECUTED_INS_ADDR, Util.getHigherWord(counter));
                    memory.set(EXECUTED_INS_ADDR + 1, Util.getLowerWord(counter));

                    return timeout;
                }
            }

            //fetch instruction
            int machineCode = memory.get(ip);

            /*
             * Contents of machineCode should look like this:
             * SSSS SDDD DDOO OOOO
             * Where S is source, D is destination and O is the opCode
             */
            Instruction instruction = instructionSet.get(machineCode & 0x03F); // 0000 0000 00XX XXXX

            int source = (machineCode >> 11) & 0x001F; // XXXX X000 0000 0000
            int destination = (machineCode >> 6) & 0x001F; // 0000 0XXX XX00 0000

            executeInstruction(instruction, source, destination);
//            LogManager.LOGGER.info(instruction.getMnemonic());
        }
        int elapsed = (int) (System.currentTimeMillis() - startTime);

        LogManager.LOGGER.fine(counter + " instruction in " + elapsed + "ms : " + (double) counter / (elapsed / 1000) / 1000000 + "MHz");


        //Write execution cost and instruction count to memory
        memory.set(EXECUTION_COST_ADDR, elapsed);
        memory.set(EXECUTED_INS_ADDR, Util.getHigherWord(counter));
        memory.set(EXECUTED_INS_ADDR + 1, Util.getLowerWord(counter));

        return elapsed;
    }

    public void executeInstruction(Instruction instruction, int source, int destination) {


        //Execute the instruction
        if (source == 0) {
            //No operand (assuming that destination is also null)
            ip++;
            instruction.execute(status);
        } else if (source == Operand.IMMEDIATE_VALUE) {
            ip++;
            int sourceValue = memory.get(ip);

            if (destination == 0) {
                //Single operand
                ip++;
                instruction.execute(sourceValue, status);
            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value too
                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with 2" +
                        "immediate values as operands"); //todo remove debug info

            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is memory immediate
                ip += 2;
                instruction.execute(memory, memory.get(ip - 1), sourceValue, status);
            } else if (destination <= registerSetSize) {
                //Destination is a register
                ip++;
                instruction.execute(registerSet, destination, sourceValue, status);

            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), sourceValue, status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1),
                        sourceValue, status);
            }

        } else if (source == Operand.IMMEDIATE_VALUE_MEM) {
            //Source is [x]
            ip++;
            int sourceValue = memory.get(memory.get(ip));

            if (destination == 0) {
                //Single operand
                ip++;
                instruction.execute(memory, memory.get(ip - 1), status);
            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value

                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with an" +
                        "immediate values as dst operand"); //todo remove debug info
            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is memory immediate too
                ip += 2;
                instruction.execute(memory, memory.get(ip - 1), sourceValue, status);
            } else if (destination <= registerSetSize) {
                //Destination is a register
                ip++;
                instruction.execute(registerSet, destination, sourceValue, status);
            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), sourceValue, status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1), sourceValue, status);
            }

        } else if (source <= registerSetSize) {
            //Source is a register

            if (destination == 0) {
                //Single operand
                ip++;
                instruction.execute(registerSet, source, status);

            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value
                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with an" +
                        "immediate values as dst operand"); //todo remove debug info
            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is memory immediate
                ip += 2;
                instruction.execute(memory, memory.get(ip - 1), registerSet, source, status);
            } else if (destination <= registerSetSize) {
                //Destination is a register too
                ip++;
                instruction.execute(registerSet, destination, registerSet, source, status);
            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), registerSet, source, status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1),
                        registerSet, source, status);
            }

        } else if (source <= registerSetSize * 2) {
            //Source is [reg]
            if (destination == 0) {
                //Single operand
                ip++;
                instruction.execute(memory, registerSet.get(source - registerSetSize), status);
            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value
                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with an" +
                        "immediate values as dst operand"); //todo remove debug info
            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is an memory immediate
                ip++;
                instruction.execute(memory, memory.get(ip++), memory, registerSet.get(source - registerSetSize), status);
            } else if (destination <= registerSetSize) {
                //Destination is a register
                ip++;
                instruction.execute(registerSet, destination, memory, registerSet.get(source - registerSetSize), status);
            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), memory, registerSet.get(source - registerSetSize), status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1),
                        memory, registerSet.get(source - registerSetSize), status);
            }
        } else {
            //Assuming that source is [reg + X]

            ip++;
            int sourceDisp = memory.get(ip);

            if (destination == 0) {
                //Single operand
                ip += 1;
                instruction.execute(memory, registerSet.get(source - registerSetSize - registerSetSize) + memory.get(ip - 1), status);

            } else if (destination == Operand.IMMEDIATE_VALUE) {
                //Destination is an immediate value
                //this shouldn't happen
                LogManager.LOGGER.severe("Trying to execute an instruction with an" +
                        "immediate values as dst operand"); //todo remove debug info
            } else if (destination == Operand.IMMEDIATE_VALUE_MEM) {
                //Destination is memory immediate
                ip += 2;
                instruction.execute(memory, memory.get(ip - 1), memory,
                        registerSet.get(source - registerSetSize - registerSetSize) + sourceDisp, status);
            } else if (destination <= registerSetSize) {
                //Destination is a register
                ip++;
                instruction.execute(registerSet, destination, memory,
                        registerSet.get(source - registerSetSize - registerSetSize) + sourceDisp, status);
            } else if (destination <= registerSetSize * 2) {
                //Destination is [reg]
                ip++;
                instruction.execute(memory, registerSet.get(destination - registerSetSize), memory,
                        registerSet.get(source - registerSetSize - registerSetSize) + sourceDisp, status);
            } else {
                //Assuming that destination is [reg + x]
                ip += 2;
                instruction.execute(memory, registerSet.get(destination - registerSetSize - registerSetSize) + memory.get(ip - 1),
                        memory, registerSet.get(source - registerSetSize - registerSetSize) + sourceDisp, status);
            }
        }
    }

    @Override
    public BasicDBObject mongoSerialise() {
        BasicDBObject dbObject = new BasicDBObject();

        dbObject.put("memory", memory.mongoSerialise());

        dbObject.put("registerSet", registerSet.mongoSerialise());
        dbObject.put("codeSegmentOffset", codeSegmentOffset);

        BasicDBList hardwareList = new BasicDBList();

        for (Integer address : attachedHardware.keySet()) {

            CpuHardware hardware = attachedHardware.get(address);

            BasicDBObject serialisedHw = hardware.mongoSerialise();
            serialisedHw.put("address", address);
            hardwareList.add(serialisedHw);
        }

        dbObject.put("hardware", hardwareList);

        return dbObject;

    }

    public static CPU deserialize(DBObject obj, User user) throws CancelledException {

        CPU cpu = new CPU(GameServer.INSTANCE.getConfig(), user);

        cpu.codeSegmentOffset = (int) obj.get("codeSegmentOffset");

        BasicDBList hardwareList = (BasicDBList) obj.get("hardware");

        for (Object serialisedHw : hardwareList) {
            CpuHardware hardware = CpuHardware.deserialize((DBObject) serialisedHw);
            hardware.setCpu(cpu);
            cpu.attachHardware(hardware, (int) ((BasicDBObject) serialisedHw).get("address"));
        }

        cpu.memory = Memory.deserialize((DBObject) obj.get("memory"));
        cpu.registerSet = RegisterSet.deserialize((DBObject) obj.get("registerSet"));

        return cpu;

    }

    public InstructionSet getInstructionSet() {
        return instructionSet;
    }

    public RegisterSet getRegisterSet() {
        return registerSet;
    }

    public Memory getMemory() {
        return memory;
    }

    public Status getStatus() {
        return status;
    }

    public int getIp() {
        return ip;
    }

    public void setIp(char ip) {
        this.ip = ip;
    }

    public void setCodeSegmentOffset(int codeSegmentOffset) {
        this.codeSegmentOffset = codeSegmentOffset;
    }

    public void attachHardware(CpuHardware hardware, int address) {
        attachedHardware.put(address, hardware);
    }

    public void detachHardware(int address) {
        attachedHardware.remove(address);
    }

    public boolean hardwareInterrupt(int address) {
        CpuHardware hardware = attachedHardware.get(address);

        if (hardware != null) {
            hardware.handleInterrupt(status);
            return true;
        } else {
            return false;
        }
    }

    public void hardwareQuery(int address) {
        CpuHardware hardware = attachedHardware.get(address);

        if (hardware != null) {

            registerSet.getRegister("B").setValue(hardware.getId());
        } else {
            registerSet.getRegister("B").setValue(0);
        }
    }

    @Override
    public String toString() {

        String str = "------------------------\n";
        str += registerSet.toString();
        str += status.toString();
        str += "------------------------\n";

        return str;
    }

    public CpuHardware getHardware(int address) {

        return attachedHardware.get(address);

    }


}
