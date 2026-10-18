package io.github.kangweizhu.backupcallingfixer;

// Runs inside a Shizuku user service process as the shell uid.
interface IPrivService {
    void destroy() = 16777114;

    String getStatus() = 1;
    String setAutoDataSwitch(int subId, boolean enabled) = 2;
    String setCrossSim(int subId, boolean enabled) = 3;
    String injectCarrierList(String packagesCsv) = 4;
    String setIwlanExcluded(boolean excluded) = 5;
    String fixAll() = 6;
    String kickIwlan() = 7;
}
