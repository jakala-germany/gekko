#include "GekkoAgentBootstrap.h"
#include <dlfcn.h>
#include <stddef.h>

void gekko_agent_bootstrap_anchor(void) {}

__attribute__((constructor))
static void gekko_agent_load(void) {
    void (*start)(void) = (void (*)(void))dlsym(RTLD_DEFAULT, "gekko_agent_start");
    if (start != NULL) {
        start();
    }
}
