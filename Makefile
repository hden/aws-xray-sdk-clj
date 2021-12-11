.PHONY: lint test repl

lint:
	clj-kondo --parallel --lint src test

test:
	lein test

repl:
	lein repl
