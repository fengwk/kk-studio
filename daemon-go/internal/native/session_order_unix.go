//go:build linux || darwin

package native

import (
	"errors"
	"sort"
)

const maxSessionTreeDepth = 64

type trackedSession struct {
	ChildPID int
	Session  int
	Identity string
}

func isDescendantOf(members []sessionMember, childPID, ancestorPID int) bool {
	if childPID <= 0 || ancestorPID <= 0 || childPID == ancestorPID {
		return false
	}
	parents := make(map[int]int, len(members))
	for _, member := range members {
		if member.PID <= 0 {
			return false
		}
		if _, exists := parents[member.PID]; exists {
			return false
		}
		parents[member.PID] = member.Parent
	}
	seen := make(map[int]struct{}, maxSessionTreeDepth)
	current := childPID
	for depth := 0; depth < maxSessionTreeDepth; depth++ {
		if _, exists := seen[current]; exists {
			return false
		}
		seen[current] = struct{}{}
		parent, exists := parents[current]
		if !exists || parent <= 0 {
			return false
		}
		if parent == ancestorPID {
			return true
		}
		current = parent
	}
	return false
}

func parentsBeforeChildren(members []sessionMember) ([]sessionMember, error) {
	byPID := make(map[int]sessionMember, len(members))
	children := make(map[int][]sessionMember, len(members))
	roots := make([]sessionMember, 0, len(members))
	for _, member := range members {
		if member.PID <= 0 {
			return nil, errors.New("process scope snapshot contains an invalid PID")
		}
		if _, exists := byPID[member.PID]; exists {
			return nil, errors.New("process scope snapshot contains a duplicate PID")
		}
		byPID[member.PID] = member
	}
	for _, member := range members {
		if _, exists := byPID[member.Parent]; exists {
			children[member.Parent] = append(children[member.Parent], member)
		} else {
			roots = append(roots, member)
		}
	}
	lessPID := func(members []sessionMember) {
		sort.Slice(members, func(i, j int) bool { return members[i].PID < members[j].PID })
	}
	lessPID(roots)
	for parent := range children {
		lessPID(children[parent])
	}
	ordered := make([]sessionMember, 0, len(members))
	var visit func(sessionMember, int) error
	visit = func(member sessionMember, depth int) error {
		if depth > maxSessionTreeDepth {
			return errors.New("process scope snapshot exceeds the maximum tree depth")
		}
		ordered = append(ordered, member)
		for _, child := range children[member.PID] {
			if err := visit(child, depth+1); err != nil {
				return err
			}
		}
		return nil
	}
	for _, root := range roots {
		if err := visit(root, 1); err != nil {
			return nil, err
		}
	}
	if len(ordered) != len(members) {
		return nil, errors.New("process scope snapshot contains a cycle")
	}
	return ordered, nil
}
