package update

import "testing"

func TestCompare(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"1.0.3", "1.0.2", 1},
		{"1.0.2", "1.0.3", -1},
		{"1.0.3", "1.0.3", 0},
		{"v1.0.3", "1.0.2", 1},
		{"1.1.0", "1.0.9", 1},
		{"2.0.0", "1.9.9", 1},
		{"dev", "1.0.0", -1},
		{"1.0.0", "dev", 1},
	}
	for _, c := range cases {
		if got := Compare(c.a, c.b); got != c.want {
			t.Fatalf("Compare(%q,%q)=%d want %d", c.a, c.b, got, c.want)
		}
	}
}
