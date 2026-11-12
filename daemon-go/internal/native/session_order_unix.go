//go:build linux || darwin

package native

import (
	"errors"
	"sort"
)

const maxSessionTreeDepth = 64

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
