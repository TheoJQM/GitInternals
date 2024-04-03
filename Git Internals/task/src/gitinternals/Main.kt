package gitinternals

import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.InflaterInputStream


const val absolutePath = "%s\\objects\\%s\\%s"
const val personCommit = "%s: %s %s %s timestamp: %s %s"
val logCommit = """
    Commit: %s
    %s
    %s
    
""".trimIndent()

class Commit(listContent: List<String>) {
    private val content = mutableMapOf("type" to "*COMMIT*", "tree" to "", "parents" to "", "author" to "", "committer" to "")
    private val commitMessageLines = mutableListOf<String>()

    init {
        for (line in listContent) {
            when (line.split(" ").first()) {
                "tree" ->  content["tree"] = line.replace(" ", ": ")
                "parent" -> content["parents"] = if (content["parents"]!!.isEmpty()) line.replace("parent ", "parents: ") else "${content["parents"]} | ${line.split(" ").last()}"
                "author" ->  content["author"] = formatString(line, "original")
                "committer" -> content["committer"] = formatString(line, "commit")
                "" -> continue
                else -> commitMessageLines.add(line)
            }
        }
    }


    private fun formatString(line: String, word: String): String {
        val myLine = line.split(" ")
        val time = formatDate(myLine[3].toLong(),myLine[4])
        val email = myLine[2].replace(Regex("""[<>]"""), "")
        val utc = StringBuilder(myLine[4]).insert(3, ":").toString()
        return personCommit.format(myLine[0], myLine[1], email, word, time, utc)

    }

    private fun formatDate(seconds: Long, utc: String): String{
        val timeDifference = utc.substring(1, 3).toLong()
        val date = Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).toLocalDateTime()
        return if (utc.first() == '+') {
            date.plusHours(timeDifference).toString().replace("T", " ")
        } else {
            date.minusHours(timeDifference).toString().replace("T", " ")
        }
    }

    fun getParent() = content["parents"]!!.removePrefix("parents: ")
    fun getCommitter() =  content["committer"]!!
    fun getMessage() = commitMessageLines.joinToString("\n")

    fun printCommit() {
        for (line in content.values) {
            if (line.isNotEmpty()) println(line)
        }
        println("commit message:")
        println(commitMessageLines.joinToString("\n"))
    }


}

class Blob(private val content: List<String>) {
    fun printBlob() {
        println("*BLOB*")
        println(content.joinToString("\n"))
    }
}

class Tree(content: MutableList<String>) {
    private val group = mutableListOf<Triple<String, String, String>>()

    init {
        for (i in 0 until content.size - 1) {
            val (metaNumber, fileName) = content[i].split(" ")
            val hash = transformHashToString(content[i + 1].take(if (content[i + 1].length > 19) 20 else content[i + 1].length))
            content[i + 1] = content[i + 1].substring(if (content[i + 1].length > 19) 20 else content[i + 1].length)
            val anElement = Triple(metaNumber, hash, fileName)
            group.add(anElement)
        }
    }

    private fun transformHashToString(hash: String): String {
        val hex = hash.map { (it.code and 0xFF).toString(16).padStart(2, '0') }
        return hex.joinToString("")
    }

    fun getGroup(): MutableList<Triple<String, String, String>> = group

    fun printTree() {
        println("*TREE*")
        group.forEach { println("${it.first} ${it.second} ${it.third}") }
    }

}

class Git {
    fun searchFile() {
        val directory = println("Enter .git directory location:").run { readln() }

        val command = println("Enter command:").run{ readln()}
        when (command) {
            "cat-file" -> printGitObject(directory)
            "list-branches" -> printBranches(directory)
            "log" -> log(directory)
            "commit-tree" -> commitTree(directory)
        }
    }

    private fun printGitObject(directory: String) {
        val objectHash = println("Enter git object hash:").run { readln() }
        val filePath = absolutePath.format(directory,objectHash.take(2), objectHash.drop(2))
        val inflate = InflaterInputStream(FileInputStream(filePath)).readAllBytes().map { Char(it.toUShort()) }.joinToString("").split("\u0000")
        val element = inflate.joinToString("\u0000").split("\u0000")

        when (element.first().split(" ").first()) {
            "commit" -> Commit(element.joinToString("\n").split("\n").drop(1)).printCommit()
            "blob" -> Blob(element.joinToString("\n").split("\n").drop(1)).printBlob()
            "tree" -> Tree(element.joinToString("\u0000").split("\u0000").drop(1).toMutableList()).printTree()
        }
    }

    private fun printBranches(directory: String) {
        val currentBranch = File("$directory\\HEAD").readText().split("/").last().trim()
        val branches = File("$directory\\refs\\heads").list()
        branches?.forEach {
            println(if (it.equals(currentBranch)) "* $it" else "  $it")
        }
    }

    private fun log(directory: String) {
        val commit = println("Enter branch name:").run { readln() }
        val commitHash = File("$directory\\refs\\heads\\$commit").readText()
        val listCommit = getAllCommit(directory, commitHash)

        listCommit.forEach { println(it)}

    }

    private fun getAllCommit(directory: String, commitHash: String, merged: Boolean = false): MutableList<String> {
        val filePath = absolutePath.format(directory,commitHash.take(2), commitHash.drop(2).trim())
        val inflate = InflaterInputStream(FileInputStream(filePath)).readAllBytes().map { Char(it.toUShort()) }.joinToString("").split("\u0000")
        val element = inflate.joinToString("\u0000").split("\u0000")

        val newCommit = Commit(element.joinToString("\n").split("\n").drop(1))
        val committer = newCommit.getCommitter().split(" ").drop(1).joinToString(" ")
        val message = newCommit.getMessage()
        val parent = newCommit.getParent().split(" | ")
        val list = mutableListOf<String>()

        if (merged) {
            list.add(logCommit.format(commitHash.trim() + " (merged)", committer, message))
        } else {
            list.add(logCommit.format(commitHash.trim(), committer, message))
        }

        when {
            parent.size == 1 && parent.first().isEmpty()  || merged -> return list
            else -> {
                if (parent.size == 2) {
                    list += getAllCommit(directory, parent.last(), true)
                }
                list += getAllCommit(directory, parent.first())
                return list
            }
        }
    }

    private fun commitTree(directory: String) {
        val commitHash = println("Enter commit-hash:").run { readln() }

        val filePath = absolutePath.format(directory,commitHash.take(2), commitHash.drop(2).trim())
        val inflate = InflaterInputStream(FileInputStream(filePath)).readAllBytes().map { Char(it.toUShort()) }.joinToString("").split("\u0000")
        val element = inflate.joinToString("\u0000").split("\u0000")
        val treeHash = element.joinToString("\n").split("\n")[1].split(" ").last()
        val tree = getTree(directory, treeHash)
        val treeContent = mutableListOf<String>()

        for (file in tree.getGroup()) {
            if (file.third.contains(".")) {
                treeContent.add(file.third)
            } else {
                val content = "${file.third}/" + getSubFile(directory, file.second)
                treeContent.add(content)
            }
        }
        treeContent.forEach { println(it) }
    }

    private fun getTree(directory: String, treeHash: String): Tree {
        val filePath = absolutePath.format(directory,treeHash.take(2), treeHash.drop(2).trim())
        val inflate = InflaterInputStream(FileInputStream(filePath)).readAllBytes().map { Char(it.toUShort()) }.joinToString("").split("\u0000")
        val element = inflate.joinToString("\u0000").split("\u0000")
        return Tree(element.joinToString("\n").split("\n").drop(1).toMutableList())
    }

    private fun getSubFile(directory: String, treeHash: String): String {
        val filePath = absolutePath.format(directory,treeHash.take(2), treeHash.drop(2).trim())
        val inflate = InflaterInputStream(FileInputStream(filePath)).readAllBytes().map { Char(it.toUShort()) }.joinToString("").split("\u0000")
        val element = inflate.joinToString("\u0000").split("\u0000")
        return Tree(element.joinToString("\n").split("\n").drop(1).toMutableList()).getGroup().first().third
    }

}

fun main() {
    val vcs = Git()
    vcs.searchFile()
}