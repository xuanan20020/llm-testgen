# LLM-Driven Unit Test Generation

This project automates Java unit test generation using a Large Language Model (LLM) and static code analysis tools.  
It extracts structural code information (AST, control flow, modifiers, class hierarchy) with **SootUp** and **JavaParser**,  
then generates and repairs tests iteratively to achieve high branch and line coverage.

---

## 🧩 Features
- Extracts rich code metadata using **SootUp** and **JavaParser**
- Structured prompt generation for each method
- Iterative error-repair loop for compiling tests
- Coverage measurement via JaCoCo / custom Python scripts
- Achieved **95%+ branch & line coverage** on Apache Commons Lang
