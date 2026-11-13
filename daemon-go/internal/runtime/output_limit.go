package runtime

import (
	"fmt"
	"math/big"
	"strconv"
	"strings"
)

const (
	bytesPerMiB    int64 = 1 << 20
	maxSignedInt64 int64 = 1<<63 - 1
)

func toolOutputExceededMessage(actualBytes, limitBytes int64) string {
	return fmt.Sprintf("Tool output is %s MiB, which exceeds the tool output size limit of %s MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.", formatActualMiB(actualBytes, limitBytes), strconv.FormatInt(limitBytes/bytesPerMiB, 10))
}

func fileOutputExceededMessage(actualBytes, limitBytes int64) string {
	return fmt.Sprintf("File is %s MiB, which exceeds the tool output size limit of %s MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.", formatActualMiB(actualBytes, limitBytes), strconv.FormatInt(limitBytes/bytesPerMiB, 10))
}

func formatActualMiB(actualBytes, limitBytes int64) string {
	actual := big.NewInt(actualBytes)
	limitMiB := limitBytes / bytesPerMiB
	for decimals := int64(1); decimals <= 6; decimals++ {
		multiplier := new(big.Int).Exp(big.NewInt(10), big.NewInt(decimals), nil)
		scaled := new(big.Int).Mul(actual, multiplier)
		scaled.Add(scaled, big.NewInt(bytesPerMiB/2))
		scaled.Div(scaled, big.NewInt(bytesPerMiB))
		limitScaled := new(big.Int).Mul(big.NewInt(limitMiB), multiplier)
		if scaled.Cmp(limitScaled) != 0 || decimals == 6 {
			whole, fraction := new(big.Int), new(big.Int)
			whole.QuoRem(scaled, multiplier, fraction)
			if fraction.Sign() == 0 {
				return whole.String()
			}
			fractionText := fraction.String()
			fractionText = strings.Repeat("0", int(decimals)-len(fractionText)) + fractionText
			fractionText = strings.TrimRight(fractionText, "0")
			return whole.String() + "." + fractionText
		}
	}
	return "0"
}
