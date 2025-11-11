package org.softwood.testScripts

def func (int num, Closure closure=null) {
    println "closure passed was " +  closure ?: "null"
    println "number was $num"
    def result
    if (closure) {
        println "closure delegate was : $closure.delegate"
        result = closure(num)
    }
    println "result from closure call was $result"
}

func (2) {
    println "it was [$it]"
    2*it
        }

