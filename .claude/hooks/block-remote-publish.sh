#!/usr/bin/env bash
# PreToolUse/Bash guard: refuse commands that would publish Repofyr artifacts
# or images to a remote registry.
#
# Why this exists rather than a permissions deny rule: deny patterns match by
# prefix, so "Bash(mvn deploy:*)" does not match "mvn -B -Prelease deploy" -
# which is precisely the command that uploads to Maven Central. Denying that
# prefix instead would also block the legitimate local staging command, since
# deny beats allow and both share the prefix. Only content inspection can tell
# the two apart, so the rule is expressed here.
#
# Local staging is allowed and is how RELEASING.md section 2 works:
#   mvn -B -Prelease deploy -DaltDeploymentRepository=staging::file:///<path>
#
# Publishing is a maintainer action performed outside Claude Code.
#
# Two rules keep this from blocking commands that merely MENTION a publish:
#
#   1. Heredoc bodies are data, not commands. A commit message, a doc edit, or
#      a `cat <<EOF` may legitimately quote `mvn -Prelease deploy`. Everything
#      from the first heredoc opener onward is ignored.
#   2. The tool name must appear in command position - at the start of a line
#      or after a shell separator - not as a substring of some argument.

set -uo pipefail

command_text=$(jq -r '.tool_input.command // ""' 2>/dev/null || echo "")

# Rule 1: drop heredoc bodies.
command_head=${command_text%%<<*}

deny() {
  jq -n --arg reason "$1" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: $reason
    }
  }'
  exit 0
}

# Rule 2: <tool> in command position on some line of the command head.
invokes() {
  printf '%s\n' "$command_head" | grep -Eq "(^|[;&|(]|&&|\|\|)[[:space:]]*$1([[:space:]]|$)"
}

contains() {
  printf '%s\n' "$command_head" | grep -Eq "$1"
}

if invokes mvn; then
  if contains '(^|[[:space:]])deploy([[:space:]]|$)' && ! contains 'altDeploymentRepository'; then
    deny "Blocked: a Maven deploy without -DaltDeploymentRepository uploads to the remote repository. Publishing is a maintainer action (RELEASING.md section 4). For a local signed staging run, use: mvn -B -Prelease deploy -DaltDeploymentRepository=staging::file:///<absolute-path>"
  fi

  if contains 'sonatype\.central|central-publishing|nexus-staging:'; then
    deny "Blocked: promoting or publishing to Maven Central is a maintainer action performed in the Central portal, not from Claude Code. See RELEASING.md section 4."
  fi
fi

if invokes docker && contains '(^|[[:space:]])push([[:space:]]|$)'; then
  deny "Blocked: pushing a container image publishes it. Image publication is a maintainer action. See RELEASING.md section 4."
fi

exit 0
