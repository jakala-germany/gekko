#ifndef GekkoAgentBootstrap_h
#define GekkoAgentBootstrap_h

/// Keeps the bootstrap object file referenced; the constructor itself starts the agent, which stays dormant unless
/// GEKKO_PORT and GEKKO_TOKEN are set.
void gekko_agent_bootstrap_anchor(void);

#endif
