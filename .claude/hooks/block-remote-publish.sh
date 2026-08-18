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
# THE RULE THAT MATTERS, learned by publishing to Central by accident on
# 2026-08-18: -DaltDeploymentRepository does NOT keep a deploy local. The
# release profile runs central-publishing-maven-plugin with
# <extensions>true</extensions>, which injects its own publish goal into the
# deploy lifecycle; that goal talks to the Central portal and ignores
# altDeploymentRepository completely. A run that looked like local staging
# created a real portal deployment. Only -DskipPublishing=true suppresses it.
#
# So a deploy is permitted only when BOTH are present:
#   -DskipPublishing=true          stops the portal upload
#   -DaltDeploymentRepository=...  routes the artifacts to a local directory
#
# Publishing is a maintainer action performed outside Claude Code.
#
# Two rules keep this from blocking commands that merely MENTION a publish:
#
#   1. Heredoc bodies are data, not commands. A commit message, a doc edit, or
#      a `cat <<EOF` may legitimately quote `mvn -Prelease deploy`. Everything
#      from the first heredoc opener onward is ignored.
#   2. Goals are matched only within the argument list of an mvn invocation,
#      not anywhere on the line. The previous version scanned the whole command
#      and denied `mvn validate` because the word "deploy" appeared in an echo
#      alongside it.

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

# Rule 2: split the command head into shell segments, then keep only the
# segments that invoke mvn, reduced to that invocation's arguments. Splitting
# on separators can also split a single invocation that contains one in a
# quoted string; that direction is safe, since it can only cause a false deny.
mvn_invocations() {
  printf '%s\n' "$command_head" \
    | tr ';&|()' '\n' \
    | grep -E '(^|[[:space:]])mvn([[:space:]]|$)' \
    | sed -E 's/^.*(^|[[:space:]])mvn([[:space:]]|$)/ /'
}

has() { printf '%s\n' "$1" | grep -Eq "$2"; }

while IFS= read -r args; do
  [ -z "$args" ] && continue

  if has "$args" '(^|[[:space:]])(deploy|deploy:deploy|deploy:deploy-file)([[:space:]]|$)'; then
    if ! has "$args" 'skipPublishing[[:space:]]*=[[:space:]]*true'; then
      deny "Blocked: this deploy would upload to Maven Central. -DaltDeploymentRepository does NOT prevent it - central-publishing-maven-plugin runs as an extension and ignores it, which is how a portal deployment was created by accident on 2026-08-18. A local staging run needs BOTH flags: mvn -B -Prelease deploy -DskipPublishing=true -DaltDeploymentRepository=staging::file:///<absolute-path>. Publishing is a maintainer action (RELEASING.md section 4)."
    fi
    if ! has "$args" 'altDeploymentRepository'; then
      deny "Blocked: a deploy without -DaltDeploymentRepository has no local target and would install to the shared local repository or a remote one. Use: mvn -B -Prelease deploy -DskipPublishing=true -DaltDeploymentRepository=staging::file:///<absolute-path>"
    fi
  fi

  if has "$args" 'sonatype\.central|central-publishing|nexus-staging:'; then
    deny "Blocked: invoking the Central publishing plugin directly is a maintainer action performed in the Central portal, not from Claude Code. See RELEASING.md section 4."
  fi
done < <(mvn_invocations)

if printf '%s\n' "$command_head" | grep -Eq "(^|[;&|(]|&&|\|\|)[[:space:]]*docker([[:space:]]|$)" \
   && printf '%s\n' "$command_head" | grep -Eq '(^|[[:space:]])push([[:space:]]|$)'; then
  deny "Blocked: pushing a container image publishes it. Image publication is a maintainer action. See RELEASING.md section 4."
fi

exit 0
